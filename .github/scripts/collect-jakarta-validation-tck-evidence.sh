#!/usr/bin/env bash
set -euo pipefail

java_version="${1:?Usage: collect-jakarta-validation-tck-evidence.sh <java-version>}"
repo_root="$(git rev-parse --show-toplevel)"
cd "${repo_root}"

evidence_dir="build/tck-evidence/jakarta-validation/jdk-${java_version}"
result_dir="tests/jakarta-validation-tck/build/test-results/jakartaTck"
summary_file="${evidence_dir}/summary.md"
index_file="${evidence_dir}/index.html"

rm -rf "${evidence_dir}"
mkdir -p "${evidence_dir}"

property_value() {
  local file="$1"
  local key="$2"
  sed -n "s/^${key}=//p" "${file}" | head -1
}

toml_version() {
  local key="$1"
  sed -n "s/^${key} = \"\\(.*\\)\"/\\1/p" gradle/libs.versions.toml | head -1
}

sha256_file() {
  local file="$1"
  if [ ! -f "${file}" ]; then
    printf '%s\n' "unavailable"
    return
  fi
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "${file}" | awk '{print $1}'
  else
    shasum -a 256 "${file}" | awk '{print $1}'
  fi
}

project_version="$(property_value gradle.properties projectVersion)"
project_group="$(property_value gradle.properties projectGroup)"
github_slug="$(property_value gradle.properties githubSlug)"
tck_version="$(toml_version jakarta-validation-tck)"
api_version="$(toml_version managed-validation)"
validation_commit="$(git rev-parse HEAD)"
generated_at="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
workflow_url="${GITHUB_SERVER_URL:-https://github.com}/${GITHUB_REPOSITORY:-${github_slug}}/actions/runs/${GITHUB_RUN_ID:-local}"
java_runtime="$(java -version 2>&1)"

tests="0"
failures="0"
errors="0"
skipped="0"
if [ -d "${result_dir}" ] && find "${result_dir}" -name 'TEST-*.xml' -print -quit | grep -q .; then
  totals="$(
    find "${result_dir}" -name 'TEST-*.xml' -print0 |
      xargs -0 awk '
        match($0, /tests="[0-9]+"/) { tests += substr($0, RSTART + 7, RLENGTH - 8) }
        match($0, /failures="[0-9]+"/) { failures += substr($0, RSTART + 10, RLENGTH - 11) }
        match($0, /errors="[0-9]+"/) { errors += substr($0, RSTART + 8, RLENGTH - 9) }
        match($0, /skipped="[0-9]+"/) { skipped += substr($0, RSTART + 9, RLENGTH - 10) }
        END { printf "%d %d %d %d", tests, failures, errors, skipped }
      '
  )"
  read -r tests failures errors skipped <<< "${totals}"
fi

status="not-run"
if [ "${tests}" != "0" ]; then
  if [ "${failures}" = "0" ] && [ "${errors}" = "0" ]; then
    status="passed"
  else
    status="failed"
  fi
fi

rm -rf "${evidence_dir}/junit-xml"
if [ -d "${result_dir}" ]; then
  mkdir -p "${evidence_dir}/junit-xml"
  for result_file in "${result_dir}"/TEST-*.xml; do
    [ -f "${result_file}" ] || continue
    perl -0pe 's#<system-out>.*?</system-out>\n?##gs; s#<system-err>.*?</system-err>\n?##gs' \
      "${result_file}" > "${evidence_dir}/junit-xml/$(basename "${result_file}")"
  done
fi

tck_cache_dir="${HOME}/.gradle/caches/modules-2/files-2.1/jakarta.validation/validation-tck-tests/${tck_version}"
tck_binary="$(find "${tck_cache_dir}" -type f -name "validation-tck-tests-${tck_version}.jar" -print -quit 2>/dev/null || true)"
tck_sources="$(find "${tck_cache_dir}" -type f -name "validation-tck-tests-${tck_version}-sources.jar" -print -quit 2>/dev/null || true)"
tck_binary_sha256="$(sha256_file "${tck_binary}")"
tck_sources_sha256="$(sha256_file "${tck_sources}")"

cat > "${summary_file}" <<EOF
# Micronaut Validation Jakarta Validation TCK Results - JDK ${java_version}

Generated: ${generated_at}

## Scope

Micronaut Validation is validating the opt-in Jakarta Validation compliance stack provided by \`micronaut-validation-jakarta\`.

This evidence is collected on JDK ${java_version}. It does not claim a full upstream verification result unless the upstream repository workflow run passed.

## Test Results

- Status: ${status}
- Tests: ${tests}
- Failures: ${failures}
- Errors: ${errors}
- Skipped: ${skipped}
- Gradle task: \`:micronaut-tests:micronaut-jakarta-validation-tck:jakartaTck\`
- Excludes: none

## Product

- Product: Micronaut Validation
- Version: ${project_version}
- Group: ${project_group}
- Repository: https://github.com/${github_slug}
- Commit: ${validation_commit}

## Specification And TCK

- Specification: Jakarta Validation 3.1
- API artifact: \`jakarta.validation:jakarta.validation-api:${api_version}\`
- TCK artifact: \`jakarta.validation:validation-tck-tests:${tck_version}\`
- TCK Maven Central URL: https://repo1.maven.org/maven2/jakarta/validation/validation-tck-tests/${tck_version}/
- TCK binary SHA-256: ${tck_binary_sha256}
- TCK sources SHA-256: ${tck_sources_sha256}

## Environment

- Workflow run: ${workflow_url}
- Runner OS: ${RUNNER_OS:-local}
- Runner architecture: ${RUNNER_ARCH:-unknown}
- Java version: ${java_version}

\`\`\`
${java_runtime}
\`\`\`

## Artifacts

- Sanitized JUnit XML: ./junit-xml/

Raw Gradle console output and unsanitized Gradle reports are intentionally not published because CI can run with secret-backed environment variables.
EOF

cat > "${index_file}" <<EOF
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Micronaut Validation Jakarta Validation TCK Results - JDK ${java_version}</title>
  <style>
    body { font-family: system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; line-height: 1.5; max-width: 960px; margin: 2rem auto; padding: 0 1rem; color: #1f2933; }
    h1, h2 { line-height: 1.2; }
    code, pre { background: #f5f7fa; border-radius: 4px; }
    code { padding: 0.1rem 0.25rem; }
    pre { padding: 1rem; overflow-x: auto; }
    table { border-collapse: collapse; width: 100%; margin: 1rem 0; }
    th, td { border: 1px solid #d9e2ec; padding: 0.5rem; text-align: left; }
    th { background: #f5f7fa; }
    .notice { border-left: 4px solid #d64545; background: #fff5f5; padding: 0.75rem 1rem; }
  </style>
</head>
<body>
  <h1>Micronaut Validation Jakarta Validation TCK Results - JDK ${java_version}</h1>
  <p>Generated: ${generated_at}</p>
  <div class="notice">
    <strong>Scope:</strong> This evidence covers the opt-in <code>micronaut-validation-jakarta</code> stack and does not claim a full upstream verification result unless the upstream workflow run passed.
  </div>
  <h2>Test Results</h2>
  <table>
    <tr><th>Status</th><th>Tests</th><th>Failures</th><th>Errors</th><th>Skipped</th></tr>
    <tr><td>${status}</td><td>${tests}</td><td>${failures}</td><td>${errors}</td><td>${skipped}</td></tr>
  </table>
  <p>Gradle task: <code>:micronaut-tests:micronaut-jakarta-validation-tck:jakartaTck</code></p>
  <p>Excludes: <code>none</code></p>
  <h2>Product</h2>
  <ul>
    <li>Product: Micronaut Validation</li>
    <li>Version: ${project_version}</li>
    <li>Repository: <a href="https://github.com/${github_slug}">${github_slug}</a></li>
    <li>Commit: <code>${validation_commit}</code></li>
  </ul>
  <h2>Specification And TCK</h2>
  <ul>
    <li>Specification: Jakarta Validation 3.1</li>
    <li>API artifact: <code>jakarta.validation:jakarta.validation-api:${api_version}</code></li>
    <li>TCK artifact: <code>jakarta.validation:validation-tck-tests:${tck_version}</code></li>
    <li>TCK binary SHA-256: <code>${tck_binary_sha256}</code></li>
    <li>TCK sources SHA-256: <code>${tck_sources_sha256}</code></li>
  </ul>
  <h2>Artifacts</h2>
  <ul>
    <li><a href="./summary.md">Markdown summary</a></li>
    <li><a href="./junit-xml/">Sanitized JUnit XML</a></li>
  </ul>
</body>
</html>
EOF
