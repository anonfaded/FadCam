#!/bin/bash
progname="${0##*/}"
progname="${progname%.sh}"

# usage: check_elf_alignment.sh [path to *.so files|path to *.apk]

cleanup_trap() {
  if [ -n "${tmp}" -a -d "${tmp}" ]; then
    rm -rf ${tmp}
  fi
  exit $1
}

usage() {
  echo "Host side script to check the ELF alignment of shared libraries."
  echo "Shared libraries are reported ALIGNED when their ELF regions are"
  echo "16 KB or 64 KB aligned. Otherwise they are reported as UNALIGNED."
  echo
  echo "Usage: ${progname} [input-path|input-APK|input-APEX]"
}

if [ ${#} -ne 1 ]; then
  usage
  exit
fi

case ${1} in
  --help | -h | -\?)
    usage
    exit
    ;;

  *)
    dir="${1}"
    ;;
esac

if ! [ -f "${dir}" -o -d "${dir}" ]; then
  echo "Invalid file: ${dir}" >&2
  exit 1
fi

RED="\e[31m"
GREEN="\e[32m"
ENDCOLOR="\e[0m"

# --- Start of APK/APEX processing for temp directory ---
# This section extracts the APK/APEX content into a temporary directory
# and updates 'dir' to point to that temporary directory.
if [[ "${dir}" == *.apk ]]; then
  trap 'cleanup_trap' EXIT

  echo
  echo "Recursively analyzing $dir"
  echo

  if { zipalign --help 2>&1 | grep -q "\-P <pagesize_kb>"; }; then
    echo "=== APK zip-alignment ==="
    zipalign -v -c -P 16 4 "${dir}" | egrep 'lib/arm64-v8a|lib/x86_64|Verification'
    echo "========================="
  else
    echo "NOTICE: Zip alignment check requires build-tools version 35.0.0-rc3 or higher."
    echo "  You can install the latest build-tools by running the below command"
    echo "  and updating your \$PATH:"
    echo
    echo "    sdkmanager \"build-tools;35.0.0-rc3\""
  fi

  dir_filename=$(basename "${dir}")
  tmp=$(mktemp -d -t "${dir_filename%.apk}_out_XXXXX")
  # Unzip only 'lib/*' contents (where ABIs are located)
  unzip "${dir}" "lib/*" -d "${tmp}" >/dev/null 2>&1
  # Update 'dir' to point to the extracted 'lib' folder within the temp directory
  dir="${tmp}/lib"
fi

if [[ "${dir}" == *.apex ]]; then
  trap 'cleanup_trap' EXIT

  echo
  echo "Recursively analyzing $dir"
  echo

  dir_filename=$(basename "${dir}")
  tmp=$(mktemp -d -t "${dir}_out_XXXXX") # Using dir_filename here was causing issues with temp names
  deapexer extract "${dir}" "${tmp}" || { echo "Failed to deapex." && exit 1; }
  dir="${tmp}" # APEX might have a different internal structure for libs, assuming 'dir' now points to the root of extracted content
fi
# --- End of APK/APEX processing for temp directory ---

unaligned_libs=()

echo
echo "=== ELF Alignment Check ==="
# Single top-level header
printf "%-100s %-20s %s\n" "FILE PATH" "STATUS" "ALIGNMENT VALUE (bytes)"
printf "%-100s %-20s %s\n" "----------------------------------------------------------------------------------------------------" "--------------------" "-----------------------"

# Define common Android ABIs to check
declare -a ABIS=("arm64-v8a" "armeabi-v7a" "x86_64" "x86" "armeabi")

# Loop through each ABI
for abi in "${ABIS[@]}"; do
  abi_path="${dir}/${abi}"

  # Check if the ABI directory exists
  if [ -d "${abi_path}" ]; then
    echo "" # Blank line for spacing
    echo -e "${GREEN}--- Analyzing ABI: ${abi} ---${ENDCOLOR}"
    # No repeated table headers here
    
    # Find all .so files within the current ABI directory
    abi_matches="$(find "${abi_path}" -type f -name "*.so")"

    if [ -z "$abi_matches" ]; then
        echo "No .so files found for ${abi}."
    fi

    IFS=$'\n' # Set Internal Field Separator to newline for handling filenames with spaces
    for match in $abi_matches; do
      # Check if it's an ELF file (using -b for brief output)
      file_output=$(file -b "${match}" 2>/dev/null)
      [[ "${file_output}" == *"ELF"* ]] || continue

      # Extract the alignment value (e.g., "2**12", "2**16") from the first LOAD segment's 'align' field
      res_raw=$(objdump -p "${match}" 2>/dev/null | awk '/LOAD/ { for (i=1; i<=NF; i++) { if ($i == "align" && (i+1) <= NF) { print $(i+1); break; } } }' | head -1)

      # Convert 2**N format to integer bytes
      alignment_value_bytes=""
      if [[ $res_raw =~ 2\*\*([0-9]+) ]]; then
        exponent=${BASH_REMATCH[1]}
        alignment_value_bytes=$((2**exponent))
      fi

      STATUS_TEXT=""
      # Check if aligned to 16KB (2**14) or 64KB (2**16)
      if [[ "$alignment_value_bytes" == "16384" || "$alignment_value_bytes" == "65536" ]]; then
        STATUS_TEXT="${GREEN}ALIGNED${ENDCOLOR}"
      else
        STATUS_TEXT="${RED}UNALIGNED${ENDCOLOR}"
        unaligned_libs+=("${match}")
      fi

      # Print formatted line
      printf "%-100s %-20b %s\n" "${match}" "${STATUS_TEXT}" "${alignment_value_bytes} ($res_raw)"
    done
    printf "%-100s %-20s %s\n" "----------------------------------------------------------------------------------------------------" "--------------------" "-----------------------"
  fi
done

echo "" # Blank line before summary
if [ ${#unaligned_libs[@]} -gt 0 ]; then
  echo -e "${RED}Found ${#unaligned_libs[@]} unaligned libs (only arm64-v8a/x86_64 libs need to be aligned).${ENDCOLOR}"
elif [ -n "${dir_filename}" ]; then
  echo -e "ELF Verification Successful"
fi
echo "==========================="