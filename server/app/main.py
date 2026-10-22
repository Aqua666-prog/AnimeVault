"""AnimeVault cloud MVP. Start with: uvicorn app.main:app --reload (from server/)."""
import os
from datetime import datetime, timedelta, timezone
from typing import Annotated, Literal
from uuid import uuid4

import jwt
from fastapi import Depends, FastAPI, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from pydantic import BaseModel, EmailStr, Field
from pwdlib import PasswordHash
from sqlalchemy import DateTime, ForeignKey, Integer, String, UniqueConstraint, create_engine, select
from sqlalchemy.orm import DeclarativeBase, Mapped, Session, mapped_column, sessionmaker

DATABASE_URL = os.environ.get("DATABASE_URL", "sqlite:///./animevault-dev.db")
JWT_SECRET = os.environ.get("JWT_SECRET")
if not JWT_SECRET or len(JWT_SECRET) < 32:
    raise RuntimeError("Set JWT_SECRET to a random secret of at least 32 characters")
engine = create_engine(DATABASE_URL, connect_args={"check_same_thread": False} if DATABASE_URL.startswith("sqlite") else {}, pool_pre_ping=True)
SessionLocal = sessionmaker(engine, expire_on_commit=False)
password_hash = PasswordHash.recommended()
bearer = HTTPBearer()


class Base(DeclarativeBase):
    pass


class User(Base):
    __tablename__ = "users"
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid4()))
    email: Mapped[str] = mapped_column(String(320), unique=True, index=True)
    password_digest: Mapped[str] = mapped_column(String(512))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc))


class WatchEntry(Base):
    __tablename__ = "watch_entries"
    __table_args__ = (UniqueConstraint("user_id", "anime_id"),)
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid4()))
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    anime_id: Mapped[int] = mapped_column(Integer)
    status: Mapped[str] = mapped_column(String(20))
    episode: Mapped[int] = mapped_column(Integer, default=0)
    position_ms: Mapped[int] = mapped_column(Integer, default=0)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))


class Credentials(BaseModel):
    email: EmailStr
    password: str = Field(min_length=12, max_length=128)


class Token(BaseModel):
    access_token: str
    token_type: str = "bearer"


class WatchUpsert(BaseModel):
    status: Literal["planning", "watching", "completed", "paused", "dropped"]
    episode: int = Field(ge=0)
    position_ms: int = Field(ge=0)


class WatchResponse(WatchUpsert):
    anime_id: int
    updated_at: datetime


app = FastAPI(title="AnimeVault API", version="0.1.0")


@app.on_event("startup")
def startup() -> None:
    # MVP bootstrap only. Replace with versioned Alembic migrations before production.
    Base.metadata.create_all(engine)


def db_session():
    with SessionLocal() as session:
        yield session


DB = Annotated[Session, Depends(db_session)]


def create_token(user_id: str) -> str:
    now = datetime.now(timezone.utc)
    return jwt.encode({"sub": user_id, "iat": now, "exp": now + timedelta(minutes=30)}, JWT_SECRET, algorithm="HS256")


def current_user(credentials: Annotated[HTTPAuthorizationCredentials, Depends(bearer)], db: DB) -> User:
    try:
        payload = jwt.decode(credentials.credentials, JWT_SECRET, algorithms=["HS256"])
        user_id = payload["sub"]
    except (jwt.InvalidTokenError, KeyError, TypeError):
        raise HTTPException(status_code=401, detail="Invalid or expired token")
    user = db.get(User, user_id)
    if user is None:
        raise HTTPException(status_code=401, detail="Account not found")
    return user


UserAuth = Annotated[User, Depends(current_user)]


@app.get("/health")
def health():
    return {"status": "ok", "service": "animevault-api"}


@app.post("/v1/auth/register", response_model=Token, status_code=status.HTTP_201_CREATED)
def register(body: Credentials, db: DB):
    email = body.email.lower()
    if db.scalar(select(User.id).where(User.email == email)):
        raise HTTPException(status_code=409, detail="Email already registered")
    user = User(email=email, password_digest=password_hash.hash(body.password))
    db.add(user)
    try:
        db.commit()
    except Exception:
        db.rollback()
        raise HTTPException(status_code=409, detail="Account could not be created")
    return Token(access_token=create_token(user.id))


@app.post("/v1/auth/login", response_model=Token)
def login(body: Credentials, db: DB):
    user = db.scalar(select(User).where(User.email == body.email.lower()))
    if not user or not password_hash.verify(body.password, user.password_digest):
        raise HTTPException(status_code=401, detail="Invalid credentials")
    return Token(access_token=create_token(user.id))


@app.get("/v1/me")
def me(user: UserAuth):
    return {"id": user.id, "email": user.email}


@app.get("/v1/library", response_model=list[WatchResponse])
def library(user: UserAuth, db: DB):
    entries = db.scalars(select(WatchEntry).where(WatchEntry.user_id == user.id).order_by(WatchEntry.updated_at.desc())).all()
    return [WatchResponse(anime_id=e.anime_id, status=e.status, episode=e.episode, position_ms=e.position_ms, updated_at=e.updated_at) for e in entries]


@app.put("/v1/library/{anime_id}", response_model=WatchResponse)
def put_entry(anime_id: int, body: WatchUpsert, user: UserAuth, db: DB):
    if anime_id <= 0:
        raise HTTPException(status_code=422, detail="anime_id must be positive")
    entry = db.scalar(select(WatchEntry).where(WatchEntry.user_id == user.id, WatchEntry.anime_id == anime_id))
    if entry is None:
        entry = WatchEntry(user_id=user.id, anime_id=anime_id)
        db.add(entry)
    entry.status = body.status
    entry.episode = body.episode
    entry.position_ms = body.position_ms
    entry.updated_at = datetime.now(timezone.utc)
    db.commit()
    db.refresh(entry)
    return WatchResponse(anime_id=entry.anime_id, status=entry.status, episode=entry.episode, position_ms=entry.position_ms, updated_at=entry.updated_at)


@app.delete("/v1/library/{anime_id}", status_code=204)
def delete_entry(anime_id: int, user: UserAuth, db: DB):
    entry = db.scalar(select(WatchEntry).where(WatchEntry.user_id == user.id, WatchEntry.anime_id == anime_id))
    if entry:
        db.delete(entry)
        db.commit()
