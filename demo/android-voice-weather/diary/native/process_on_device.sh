#!/system/bin/sh
# Run on NXP after deployment. Does not record audio or use the network.
set -eu
umask 077
[ "$#" -eq 2 ] || [ "$#" -eq 3 ] || { echo 'Usage: process_on_device.sh input.wav NEW-output-directory [enhanced-ratio-0-to-1]' >&2; exit 2; }
runtime_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
input_file=$1
output_dir=$2
wet_ratio=${3:-1}
[ -f "$input_file" ] || { echo 'Input file missing' >&2; exit 2; }
[ ! -e "$output_dir" ] || { echo 'Refusing to overwrite existing output directory' >&2; exit 2; }
mkdir -m 700 "$output_dir"
export LD_LIBRARY_PATH="$runtime_dir/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
printf 'Processing, not yet validated for transcript accuracy\n' > "$output_dir/running"
"$runtime_dir/nxp-offline-speech" enhance "$runtime_dir/models/gtcrn_simple.onnx" "$input_file" "$output_dir/enhanced.wav" > "$output_dir/enhancement.log" 2>&1
asr_input="$output_dir/enhanced.wav"
if [ "$wet_ratio" != "1" ]; then
  asr_input="$output_dir/enhanced-conservative.wav"
  "$runtime_dir/nxp-offline-speech" blend "$input_file" "$output_dir/enhanced.wav" "$asr_input" "$wet_ratio" > "$output_dir/blend.log" 2>&1
fi
"$runtime_dir/nxp-offline-speech" transcribe "$runtime_dir/models/zipformer14m" "$asr_input" "$output_dir/transcript.jsonl" > "$output_dir/transcription.log" 2>&1
mv "$output_dir/running" "$output_dir/processing-complete"
echo 'All samples processed. Transcript accuracy still requires listening and verification.'
