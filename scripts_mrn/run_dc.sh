#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/.." && pwd)"
cd "${repo_root}"

export TOP_NAME="${TOP_NAME:-MergeReductionTree}"
export RTL_FILE="${RTL_FILE:-${repo_root}/rtl/MergeReductionTree.v}"
export CLOCK_PERIOD_NS="${CLOCK_PERIOD_NS:-2.0}"
export OUTPUT_ROOT="${OUTPUT_ROOT:-${repo_root}/target/dc_mrn}"
export DC_SETUP_FILE="${DC_SETUP_FILE:-${script_dir}/synopsys_dc.setup}"

if [[ ! -f "${RTL_FILE}" ]]; then
  echo "RTL file not found: ${RTL_FILE}" >&2
  echo "Generate it first with: sbt 'runMain GenerateMergeReductionTree'" >&2
  exit 1
fi

if [[ ! -f "${DC_SETUP_FILE}" ]]; then
  echo "DC setup file not found: ${DC_SETUP_FILE}" >&2
  exit 1
fi

if ! command -v dc_shell >/dev/null 2>&1; then
  echo "dc_shell was not found in PATH" >&2
  exit 127
fi

exec dc_shell -64bit -f "${script_dir}/pre_dc.tcl"
