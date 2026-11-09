#include <jni.h>
#include <llm/llm.hpp>
#include <memory>
#include <new>
#include <sstream>
#include <stdexcept>
#include <string>

using MNN::Transformer::Llm;
using MNN::Transformer::LlmStatus;
namespace {
struct Session {
    // The stream must outlive all native generation calls.
    std::ostringstream output;
    std::unique_ptr<Llm, decltype(&Llm::destroy)> llm{nullptr, &Llm::destroy};
};

Session& session(jlong handle) {
    if (!handle) throw std::runtime_error("MNN session is not loaded");
    return *reinterpret_cast<Session*>(handle);
}

std::string utf8(JNIEnv* env, jbyteArray bytes) {
    if (!bytes) throw std::invalid_argument("Missing UTF-8 input");
    const auto count = env->GetArrayLength(bytes);
    std::string result(static_cast<size_t>(count), '\0');
    env->GetByteArrayRegion(bytes, 0, count, reinterpret_cast<jbyte*>(result.data()));
    if (env->ExceptionCheck()) throw std::runtime_error("Could not read JNI input");
    return result;
}

void translateException(JNIEnv* env) {
    if (env->ExceptionCheck()) return;
    const char* type;
    std::string message;
    try { throw; }
    catch (const std::bad_alloc&) { type = "java/lang/OutOfMemoryError"; message = "MNN native allocation failed"; }
    catch (const std::invalid_argument& e) { type = "java/lang/IllegalArgumentException"; message = e.what(); }
    catch (const std::exception& e) { type = "java/lang/IllegalStateException"; message = e.what(); }
    catch (...) { type = "java/lang/IllegalStateException"; message = "Unknown native MNN failure"; }
    env->ThrowNew(env->FindClass(type), message.c_str());
}

void checkStatus(Session& s) {
    const auto status = s.llm->getContext()->status;
    if (status == LlmStatus::INTERNAL_ERROR || status == LlmStatus::NOT_LOADED || status == LlmStatus::TIMEOUT) {
        throw std::runtime_error("MNN inference failed or timed out");
    }
}
} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_sergey_animevault_localai_MnnNative_create(JNIEnv* env, jobject, jbyteArray path) {
    try {
        const auto config = utf8(env, path);
        if (config.find('\0') != std::string::npos) throw std::invalid_argument("Invalid model path");
        auto s = std::make_unique<Session>();
        s->llm.reset(Llm::createLLM(config));
        if (!s->llm) throw std::runtime_error("MNN createLLM failed");
        if (!s->llm->set_config(R"({"backend_type":"cpu","thread_num":2,"memory":"low","precision":"low","use_template":true,"reuse_kv":false})")) {
            throw std::runtime_error("MNN rejected runtime configuration");
        }
        if (!s->llm->load()) throw std::runtime_error("MNN load failed");
        return reinterpret_cast<jlong>(s.release());
    } catch (...) { translateException(env); return 0; }
}

extern "C" JNIEXPORT void JNICALL
Java_com_sergey_animevault_localai_MnnNative_begin(
    JNIEnv* env, jobject, jlong handle, jobjectArray roles, jobjectArray texts, jint maxInputTokens) {
    try {
        auto& s = session(handle);
        if (!roles || !texts || env->GetArrayLength(roles) != env->GetArrayLength(texts)) {
            throw std::invalid_argument("Invalid conversation");
        }
        MNN::Transformer::ChatMessages history;
        for (jsize i = 0; i < env->GetArrayLength(roles); ++i) {
            auto role = static_cast<jbyteArray>(env->GetObjectArrayElement(roles, i));
            auto text = static_cast<jbyteArray>(env->GetObjectArrayElement(texts, i));
            history.emplace_back(utf8(env, role), utf8(env, text));
            env->DeleteLocalRef(role);
            env->DeleteLocalRef(text);
        }
        s.llm->reset();
        const auto prompt = s.llm->apply_chat_template(history);
        const auto ids = s.llm->tokenizer_encode(prompt);
        if (ids.empty() || static_cast<int>(ids.size()) > maxInputTokens) {
            throw std::invalid_argument("Conversation exceeds context token budget");
        }
        s.output.str("");
        s.output.clear();
        // Prefill only, as in the official Android LlmSession implementation.
        // Empty end marker avoids leaking internal control strings into text.
        s.llm->response(ids, &s.output, "", 0);
        checkStatus(s);
    } catch (...) { translateException(env); }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_sergey_animevault_localai_MnnNative_step(JNIEnv* env, jobject, jlong handle) {
    try {
        auto& s = session(handle);
        s.output.str("");
        s.output.clear();
        s.llm->generate(1);
        checkStatus(s);
        const auto bytes = s.output.str();
        auto result = env->NewByteArray(static_cast<jsize>(bytes.size()));
        if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(bytes.size()),
                                           reinterpret_cast<const jbyte*>(bytes.data()));
        return result;
    } catch (...) { translateException(env); return nullptr; }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_sergey_animevault_localai_MnnNative_finished(JNIEnv* env, jobject, jlong handle) {
    try { return session(handle).llm->stoped(); }
    catch (...) { translateException(env); return JNI_TRUE; }
}

extern "C" JNIEXPORT void JNICALL
Java_com_sergey_animevault_localai_MnnNative_release(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<Session*>(handle);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_sergey_animevault_localai_MnnNative_metrics(JNIEnv* env, jobject, jlong handle) {
    try {
        const auto* context = session(handle).llm->getContext();
        const jlong values[] = {context->load_us, context->prefill_us, context->decode_us, context->gen_seq_len};
        auto result = env->NewLongArray(4);
        if (result) env->SetLongArrayRegion(result, 0, 4, values);
        return result;
    } catch (...) { translateException(env); return nullptr; }
}
