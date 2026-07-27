// 기기 안 모델을 부르는 통로.
//
// 여기를 지나는 프롬프트에는 금고에서 꺼낸 값이 그대로 들어 있다(SecretFiller가
// 채울 값을 계획에 실어 보낸다). 그래서 이 파일은 프롬프트도, 생성된 답도
// 로그로 내보내지 않는다. 길이와 개수만 남긴다.

#include <jni.h>
#include <android/log.h>

#include <string>
#include <vector>

#include "chat.h"
#include "llama.h"

#define LOG_TAG "llamajni"
#define LOGi(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGe(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// 모델 하나에 딸린 것들을 한 덩어리로 들고 있다가 free에서 통째로 정리한다.
struct Session {
    llama_model             * model   = nullptr;
    llama_context           * ctx     = nullptr;
    llama_sampler           * sampler = nullptr;
    common_chat_templates_ptr templates;
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

/**
 * 이 한 번의 생성에 쓸 샘플러.
 *
 * temperature <= 0이면 그리디다. 기본은 그리디 — 같은 계획에는 같은 답이 나와야
 * 대조 결과를 믿을 수 있다. 다시 물을 때만 온도를 준다. 그리디로 다시 물어봤자
 * 글자 하나까지 같은 답이 돌아오기 때문이다.
 */
llama_sampler * make_sampler(float temperature) {
    llama_sampler * chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(chain, llama_sampler_init_temp(temperature));
        // 씨앗을 고정한다. 다시 물을 때마다 다른 답이 나오면 무엇 때문에 통과했는지
        // 알 수 없고, 실패를 다시 재현할 수도 없다.
        llama_sampler_chain_add(chain, llama_sampler_init_dist(1234));
    }
    return chain;
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

    auto * session = new Session{model, ctx, nullptr, common_chat_templates_init(model, "")};
    LOGi("모델 로딩 완료 (%.1f MiB), 템플릿=%s",
         (double) llama_model_size(model) / (1024.0 * 1024.0),
         common_chat_templates_source(session->templates.get()).c_str());
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

/**
 * system/user 한 쌍을 모델의 챗 템플릿에 씌워 한 번 생성한다.
 *
 * 템플릿은 common(minja)이 gguf 안의 지니자 원본을 그대로 렌더링한다.
 * llama.h의 llama_chat_apply_template을 쓰지 않는 이유는 파일 맨 위에 적었다.
 *
 * enable_thinking=false가 핵심이다. EXAONE 4.0은 추론 모델이라 그냥 두면
 * 답 대신 생각을 쓴다 — 실측으로 "지시문을 그대로 답하라"는 프롬프트에
 * 지시문까지 따라 적었다. 템플릿이 빈 <think></think>를 미리 닫아주면
 * 첫 토큰부터 답이 나온다.
 *
 * 프롬프트가 n_ctx를 넘으면 생성하지 않고 알린다. 넘긴 채로 밀어 넣으면
 * 앞부분이 잘려나가는데, 잘리는 앞부분이 하필 지시문이다.
 */
JNIEXPORT jstring JNICALL
Java_com_example_mobileguiagent_llm_LlamaBridge_nativeChat(
        JNIEnv * env, jobject, jlong handle, jstring jsystem, jstring juser, jint max_tokens,
        jfloat temperature) {
    auto * s = reinterpret_cast<Session *>(handle);
    if (s == nullptr) {
        return env->NewStringUTF("ERROR: session이 null");
    }

    llama_sampler_free(s->sampler);
    s->sampler = make_sampler(temperature);

    common_chat_templates_inputs inputs;
    inputs.use_jinja             = true;
    inputs.add_generation_prompt = true;
    inputs.enable_thinking       = false;
    inputs.reasoning_format      = COMMON_REASONING_FORMAT_NONE;
    inputs.messages = {
        {.role = "system", .content = to_utf8(env, jsystem)},
        {.role = "user",   .content = to_utf8(env, juser)},
    };

    std::string prompt;
    try {
        prompt = common_chat_templates_apply(s->templates.get(), inputs).prompt;
    } catch (const std::exception & e) {
        LOGe("챗 템플릿 렌더링 실패: %s", e.what());
        return env->NewStringUTF("ERROR: 챗 템플릿 렌더링 실패");
    }

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

    const int32_t n_ctx = (int32_t) llama_n_ctx(s->ctx);
    LOGi("프롬프트 토큰 %d개 / n_ctx %d", n_prompt, n_ctx);
    if (n_prompt + max_tokens > n_ctx) {
        const std::string message =
                "ERROR: 프롬프트가 컨텍스트를 넘습니다(" + std::to_string(n_prompt) +
                "+" + std::to_string(max_tokens) + " > " + std::to_string(n_ctx) + ")";
        return env->NewStringUTF(message.c_str());
    }

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
