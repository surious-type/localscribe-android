#!/usr/bin/env bash
# Validate a strict Android release tag and deterministic semantic version code.
set -euo pipefail

usage() { printf 'Usage: %s [--version-name NAME --version-code CODE] vX.Y.Z\n' "$0" >&2; exit 64; }

version_name=''
version_code=''
if [[ ${1:-} == '--version-name' ]]; then
    [[ $# -eq 5 && ${3:-} == '--version-code' ]] || usage
    version_name=$2
    version_code=$4
    tag=$5
else
    [[ $# -eq 1 ]] || usage
    tag=$1
    build_file='app/build.gradle.kts'
    [[ -f "$build_file" ]] || { printf 'Missing %s\n' "$build_file" >&2; exit 66; }
    version_name=$(sed -nE 's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' "$build_file")
    version_code=$(sed -nE 's/^[[:space:]]*versionCode[[:space:]]*=[[:space:]]*([0-9]+).*/\1/p' "$build_file")
fi

[[ $tag =~ ^v([0-9]+)\.([0-9]+)\.([0-9]+)$ ]] || { printf 'Tag must be exactly vX.Y.Z: %s\n' "$tag" >&2; exit 65; }
major=${BASH_REMATCH[1]}
minor=${BASH_REMATCH[2]}
patch=${BASH_REMATCH[3]}
[[ $major =~ ^(0|[1-9][0-9]*)$ && $minor =~ ^(0|[1-9][0-9]*)$ && $patch =~ ^(0|[1-9][0-9]*)$ ]] || { printf 'Tag components must use canonical decimal notation\n' >&2; exit 65; }
[[ $version_name =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { printf 'versionName must be numeric semantic values\n' >&2; exit 65; }
[[ $version_code =~ ^[1-9][0-9]*$ ]] || { printf 'versionCode must be a positive canonical decimal value\n' >&2; exit 65; }
[[ ${#version_code} -lt 10 || ( ${#version_code} -eq 10 && ( $version_code < 2100000000 || $version_code == 2100000000 ) ) ]] || { printf 'versionCode exceeds Android supported limit: %s\n' "$version_code" >&2; exit 65; }
[[ ${#major} -le 4 && ${#minor} -le 3 && ${#patch} -le 3 ]] || { printf 'Version components exceed supported bounds\n' >&2; exit 65; }
(( 10#$major <= 2100 && 10#$minor <= 999 && 10#$patch <= 999 )) || { printf 'Version components exceed supported bounds\n' >&2; exit 65; }
expected_name=${tag#v}
[[ $version_name == "$expected_name" ]] || { printf 'Tag %s does not match versionName %s\n' "$tag" "$version_name" >&2; exit 65; }
expected_code=$((10#$major * 1000000 + 10#$minor * 1000 + 10#$patch))
(( expected_code <= 2100000000 )) || { printf 'versionCode exceeds Android supported limit: %d\n' "$expected_code" >&2; exit 65; }
[[ $version_code -eq $expected_code ]] || { printf 'versionCode must be %d for %s, got %s\n' "$expected_code" "$version_name" "$version_code" >&2; exit 65; }
printf 'Validated %s: versionName=%s versionCode=%s\n' "$tag" "$version_name" "$version_code"
