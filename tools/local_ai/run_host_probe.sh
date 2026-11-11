#!/usr/bin/env bash
# Requires a host C++ compiler and CMake/Ninja. No Android, JNI, mocks or downloaded SDK.
set -euo pipefail
repo_dir=$(cd "$(dirname "$0")/../.." && pwd)
build_dir=${MNN_HOST_BUILD_DIR:-"$repo_dir/build/mnn-host"}
model_dir=${1:?Pass directory containing the verified official model}
output_dir=${2:?Pass output directory}
mkdir -p "$output_dir"
cmake -S "$repo_dir/third_party/MNN" -B "$build_dir" -G Ninja \
  -DCMAKE_BUILD_TYPE=Release -DMNN_BUILD_LLM=ON -DMNN_LOW_MEMORY=ON \
  -DMNN_CPU_WEIGHT_DEQUANT_GEMM=ON -DMNN_SUPPORT_TRANSFORMER_FUSE=ON \
  -DMNN_BUILD_LLM_OMNI=OFF -DLLM_SUPPORT_HTTP_RESOURCE=OFF \
  -DMNN_BUILD_TOOLS=OFF -DMNN_BUILD_DEMO=OFF
cmake --build "$build_dir" --target llm_demo -j2
c++ -std=c++17 -O2 "$repo_dir/tools/local_ai/host_probe.cpp" \
  -I "$repo_dir/third_party/MNN/include" -I "$repo_dir/third_party/MNN/transformers/llm/engine/include" \
  -L "$build_dir" -L "$build_dir/express" -Wl,-rpath,"$build_dir" -Wl,-rpath,"$build_dir/express" \
  -lllm -lMNN_Express -lMNN -o "$build_dir/host_probe"
"$build_dir/host_probe" "$model_dir/config.json" "$repo_dir/tools/local_ai/russian-prompts.txt" "$output_dir" "$repo_dir/tools/local_ai/chat-system.txt"
