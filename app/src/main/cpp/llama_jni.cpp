// llama.cpp를 안드로이드에서 부를 수 있는지 확인하기 위한 최소 JNI 껍데기다.
// 앱 기능에 연결하지 않는다. 모델을 올리고, 한 번 생성해보고, 내려놓는 것까지만 한다.

#include <jni.h>
#include <android/log.h>

#include <string>
#include <vector>

#include "llama.h"

#define LOG_TAG "llamajni"
#define LOGi(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGe(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// 모델 하나에 딸린 것들을 한 덩어리로 들고 있다가 free에서 통째로 정리한다.
struct Session {
    llama_model   * model   = nullptr;
    llama_context * ctx     = nullptr;
    llama_sampler * sampler = nullptr;
};

std::string to_utf8(JNIEnv * env, jstring s) {
    const char * chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars ? chars : "");
    if (chars) {
        env->ReleaseStringUTFChars(s, chars);
    }
    return out;
}

// llama.cpp 내부 로그를 logcat으로 흘려보낸다. 실기기에서 모델 로딩이
// 실패했을 때 이유를 볼 수 있는 유일한 창구라 붙여둔다.
void log_callback(ggml_log_level level, const char * text, void * /*user_data*/) {
    int prio = ANDROID_LOG_INFO;
    if (level == GGML_LOG_LEVEL_ERROR) {
        prio = ANDROID_LOG_ERROR;
    } else if (level == GGML_LOG_LEVEL_WARN) {
        prio = ANDROID_LOG_WARN;
    }
    __android_log_print(prio, "llama.cpp", "%s", text);
}

bool g_backend_ready = false;

void ensure_backend() {
    if (!g_backend_ready) {
        llama_log_set(log_callback, nullptr);
        llama_backend_init();
        g_backend_ready = true;
    }
}

std::string piece_of(const llama_vocab * vocab, llama_token token) {
    char buf[256];
    const int n = llama_token_to_piece(vocab, token, buf, sizeof(buf), 0, /*special=*/false);
    if (n < 0) {
        return "";
    }
    return std::string(buf, n);
}

} // namespace

extern "C" {

// 1단계: 모델 없이 네이티브 라이브러리가 실제로 로드되고 불리는지만 본다.
JNIEXPORT jstring JNICALL
Java_com_example_mobileguiagent_llm_LlamaBridge_nativeBuildInfo(JNIEnv * env, jobject) {
    ensure_backend();
    std::string info = "llama.cpp ok; ";
    info += "sysinfo=";
    info += llama_print_system_info();
    return env->NewStringUTF(info.c_str());
}

// 2단계: gguf를 올린다. 실패하면 0을 돌려주고 이유는 logcat에 남는다.
JNIEXPORT jlong JNICALL
Java_com_example_mobileguiagent_llm_LlamaBridge_nativeLoadModel(
        JNIEnv * env, jobject, jstring jpath, jint n_ctx, jint n_threads) {
    ensure_backend();

    const std::string path = to_utf8(env, jpath);
    LOGi("모델 로딩: %s (n_ctx=%d, n_threads=%d)", path.c_str(), n_ctx, n_threads);

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0; // 이번 확인은 CPU만 쓴다.

    llama_model * model = llama_model_load_from_file(path.c_str(), mparams);
    if (model == nullptr) {
        LOGe("모델 로딩 실패: %s", path.c_str());
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx           = (uint32_t) n_ctx;
    cparams.n_batch         = (uint32_t) n_ctx;
    cparams.n_threads       = n_threads;
    cparams.n_threads_batch = n_threads;

    llama_context * ctx = llama_init_from_model(model, cparams);
    if (ctx == nullptr) {
        LOGe("컨텍스트 생성 실패");
        llama_model_free(model);
        return 0;
    }

    // 에코를 확인하는 자리라 샘플링은 그리디로 못 박는다. 같은 입력이면
    // 같은 출력이 나와야 "됐다/안 됐다"를 판단할 수 있다.
    llama_sampler * sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_greedy());

    auto * session = new Session{model, ctx, sampler};
    LOGi("모델 로딩 완료 (%.1f MiB)", (double) llama_model_size(model) / (1024.0 * 1024.0));
    return reinterpret_cast<jlong>(session);
}

JNIEXPORT jstring JNICALL
Java_com_example_mobileguiagent_llm_LlamaBridge_nativeModelInfo(JNIEnv * env, jobject, jlong handle) {
    auto * s = reinterpret_cast<Session *>(handle);
    if (s == nullptr) {
        return env->NewStringUTF("");
    }
    char desc[256] = {0};
    llama_model_desc(s->model, desc, sizeof(desc));

    std::string out = desc;
    out += "; size=" + std::to_string(llama_model_size(s->model) / (1024 * 1024)) + "MiB";
    out += "; n_ctx_train=" + std::to_string(llama_model_n_ctx_train(s->model));
    out += "; n_ctx=" + std::to_string(llama_n_ctx(s->ctx));

    const char * tmpl = llama_model_chat_template(s->model, nullptr);
    out += std::string("; chat_template=") + (tmpl ? "있음" : "없음");
    return env->NewStringUTF(out.c_str());
}

// 3단계: 짧은 생성.
//
// apply_template=true면 llama_chat_apply_template을 쓴다. 다만 이 함수는 지니자
// 렌더러가 아니라 llama.cpp가 손으로 옮겨 적은 템플릿 목록을 쓴다. EXAONE 4.0의
// 경우 그 사본이 gguf에 든 실제 템플릿과 달라서(개행, [|endofturn|], <think>
// 블록이 빠진다) 모델이 추론 모드에 갇힌다. 그래서 호출 측이 프롬프트를 이미
// 포맷해 넘길 수 있도록 apply_template=false 경로를 둔다.
JNIEXPORT jstring JNICALL
Java_com_example_mobileguiagent_llm_LlamaBridge_nativeGenerate(
        JNIEnv * env, jobject, jlong handle, jstring jprompt, jint max_tokens,
        jboolean apply_template) {
    auto * s = reinterpret_cast<Session *>(handle);
    if (s == nullptr) {
        return env->NewStringUTF("ERROR: session이 null");
    }

    const std::string user = to_utf8(env, jprompt);

    std::string prompt = user;
    const char * tmpl = apply_template ? llama_model_chat_template(s->model, nullptr) : nullptr;
    if (tmpl != nullptr) {
        llama_chat_message msg{"user", user.c_str()};
        std::vector<char> buf(user.size() * 4 + 2048);
        const int32_t n = llama_chat_apply_template(
                tmpl, &msg, 1, /*add_ass=*/true, buf.data(), (int32_t) buf.size());
        if (n > 0 && n <= (int32_t) buf.size()) {
            prompt.assign(buf.data(), n);
        } else {
            LOGi("챗 템플릿 적용 실패(%d) — 프롬프트를 그대로 쓴다", n);
        }
    }
    LOGi("최종 프롬프트 >>>%s<<<", prompt.c_str());

    // 한 번 부르는 것이 한 번의 대화다. 앞선 호출의 KV가 남아 있으면 다음
    // 호출이 그걸 대화 기록으로 읽는다.
    llama_memory_clear(llama_get_memory(s->ctx), true);

    const llama_vocab * vocab = llama_model_get_vocab(s->model);

    // 토큰 수를 모르니 음수 반환으로 필요한 크기를 먼저 받아온다.
    int32_t n_prompt = -llama_tokenize(
            vocab, prompt.c_str(), (int32_t) prompt.size(), nullptr, 0, true, true);
    std::vector<llama_token> tokens(n_prompt);
    n_prompt = llama_tokenize(
            vocab, prompt.c_str(), (int32_t) prompt.size(),
            tokens.data(), (int32_t) tokens.size(), true, true);
    if (n_prompt < 0) {
        return env->NewStringUTF("ERROR: 토크나이즈 실패");
    }
    tokens.resize(n_prompt);
    LOGi("프롬프트 토큰 %d개", n_prompt);

    if (llama_decode(s->ctx, llama_batch_get_one(tokens.data(), (int32_t) tokens.size())) != 0) {
        return env->NewStringUTF("ERROR: 프롬프트 decode 실패");
    }

    std::string out;
    int n_decoded = 0;
    for (int i = 0; i < max_tokens; i++) {
        llama_token id = llama_sampler_sample(s->sampler, s->ctx, -1);
        if (llama_vocab_is_eog(vocab, id)) {
            break;
        }
        out += piece_of(vocab, id);
        n_decoded++;

        if (llama_decode(s->ctx, llama_batch_get_one(&id, 1)) != 0) {
            out += "\n[decode 중단]";
            break;
        }
    }
    LOGi("생성 토큰 %d개", n_decoded);
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT void JNICALL
Java_com_example_mobileguiagent_llm_LlamaBridge_nativeFree(JNIEnv *, jobject, jlong handle) {
    auto * s = reinterpret_cast<Session *>(handle);
    if (s == nullptr) {
        return;
    }
    llama_sampler_free(s->sampler);
    llama_free(s->ctx);
    llama_model_free(s->model);
    delete s;
}

} // extern "C"
