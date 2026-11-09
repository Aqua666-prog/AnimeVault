// Real MNN CPU probe. Uses the same reset/template/tokenizer/prefill/step path as JNI.
#include <llm/llm.hpp>
#include <chrono>
#include <fstream>
#include <memory>
#include <sstream>
using namespace MNN::Transformer;
using Clock = std::chrono::steady_clock;
static double ms(Clock::time_point start) {
    return std::chrono::duration<double, std::milli>(Clock::now() - start).count();
}
int main(int argc, char** argv) {
    if (argc != 4 && argc != 5) return 2; // config, one-prompt-per-line input, existing output directory
    std::ostringstream stream;
    std::unique_ptr<Llm, decltype(&Llm::destroy)> llm(Llm::createLLM(argv[1]), &Llm::destroy);
    if (!llm || !llm->set_config(R"({"backend_type":"cpu","thread_num":2,"memory":"low","precision":"low","use_template":true,"reuse_kv":false})")) return 3;
    auto loadStart = Clock::now();
    if (!llm->load()) return 4;
    std::ofstream metrics(std::string(argv[3]) + "/metrics.tsv");
    metrics << "load_ms\t" << ms(loadStart) << "\nindex\tinput_tokens\tgenerated_tokens\tprefill_us\tdecode_us\tttft_ms\ttotal_ms\tstatus\n";
    std::string system;
    if (argc == 5) {
        std::ifstream input(argv[4]);
        system.assign(std::istreambuf_iterator<char>(input), std::istreambuf_iterator<char>());
    }
    std::ifstream prompts(argv[2]);
    std::string prompt;
    int index = 0;
    bool failed = false;
    while (std::getline(prompts, prompt)) {
        llm->reset();
        stream.str(""); stream.clear();
        ChatMessages messages;
        if (!system.empty()) messages.emplace_back("system", system);
        messages.emplace_back("user", prompt);
        const auto ids = llm->tokenizer_encode(llm->apply_chat_template(messages));
        if (ids.empty() || ids.size() > 1792) return 5;
        auto start = Clock::now();
        double first = -1;
        llm->response(ids, &stream, "", 0);
        int steps = 0;
        while (!llm->stoped() && steps++ < 256 && ms(start) < 180000) {
            llm->generate(1);
            if (first < 0 && !stream.str().empty()) first = ms(start);
            if (llm->getContext()->status == LlmStatus::INTERNAL_ERROR) break;
        }
        const auto* ctx = llm->getContext();
        const bool error = ctx->status == LlmStatus::INTERNAL_ERROR || ctx->status == LlmStatus::NOT_LOADED || ctx->status == LlmStatus::TIMEOUT || stream.str().empty() || ms(start) >= 180000;
        metrics << index << '\t' << ids.size() << '\t' << ctx->gen_seq_len << '\t' << ctx->prefill_us << '\t' << ctx->decode_us << '\t' << first << '\t' << ms(start) << '\t' << (error ? "error" : llm->stoped() ? "eos" : "token_limit") << '\n';
        metrics.flush();
        std::ofstream(std::string(argv[3]) + "/" + std::to_string(index) + ".txt") << stream.str();
        failed = failed || error;
        ++index;
    }
    return failed || index == 0 ? 6 : 0;
}
