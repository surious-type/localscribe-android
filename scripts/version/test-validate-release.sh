#!/usr/bin/env bash
set -euo pipefail

validator="$(dirname "$0")/validate-release.sh"

"$validator" --version-name 0.1.0 --version-code 1000 v0.1.0
"$validator" --version-name 2100.0.0 --version-code 2100000000 v2100.0.0

for case_args in \
    '--version-name 01.0.0 --version-code 1000000 v01.0.0' \
    '--version-name 1.00.0 --version-code 1000000 v1.00.0' \
    '--version-name 0.0.0 --version-code 0 v0.0.0' \
    '--version-name 0.1.0 --version-code 0001000 v0.1.0' \
    '--version-name 0.1.0 --version-code 18446744073709552616 v0.1.0' \
    '--version-name 0.1000.0 --version-code 1000000 v0.1000.0' \
    '--version-name 0.999.1000 --version-code 1000000 v0.999.1000' \
    '--version-name 2100.0.1 --version-code 2100000001 v2100.0.1' \
    '--version-name 1.0.0 --version-code 1000000 v0.1000.0'; do
    # Deliberately split fixed test literals, not user-controlled input.
    read -r -a arguments <<< "$case_args"
    if "$validator" "${arguments[@]}"; then
        printf 'Expected rejection: %s\n' "$case_args" >&2
        exit 1
    fi
done

printf 'Version validator boundary tests passed\n'
