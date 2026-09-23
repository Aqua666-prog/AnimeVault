# AnimeVault 1.4.0 — Vault Design System

1.4.0 is the foundation stage of the full AnimeVault redesign. It deliberately
changes the visual language and reusable UI primitives before the later screen-level
recomposition of Home, Library, Online and Player.

## Design tokens

New `ui/design` primitives define spacing, radius, interactive sizes, opacity and
motion. Shared screens no longer need to invent basic geometry and animation timing.

## Semantic surfaces

`VaultPanel` introduces Quiet, Card, Glass, Elevated and Accent roles. These roles
separate information hierarchy from arbitrary alpha/border choices and provide a
stable base for phone, tablet and later TV layouts.

## Vault Nocturne identity

- revised graphite/ink palette with violet, aqua and rose accents;
- restrained editorial typography using the system Sans Serif;
- subtle concentric "vault door" rings integrated into the global backdrop;
- reusable `VaultLogoMark` with a vault-dial motif;
- squircle top-bar actions instead of generic round Material buttons.

## Shared components migrated

- search field;
- glass/card surfaces;
- section headers;
- status pills;
- empty/error states;
- primary action button;
- skeleton loading blocks;
- watch progress;
- library/online section switch;
- filter chips used by the main library/catalog/title screens;
- navigation and hero motion tokens.

## Compatibility

- database version is unchanged;
- invalid placeholder Room schema files are not shipped;
- completion threshold remains 92%;
- local scanner, online providers, stream fallback and player input behavior are not
  intentionally changed by 1.4.0.

## Next redesign stages

The design system is intentionally reusable by the planned 1.4.x screen passes:
Home hero/dashboard, Library layouts, Online catalog/source picker, Player chrome and
Settings information architecture.
