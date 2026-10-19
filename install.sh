#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Convenience entry point; see installer/install.sh --help
exec "$(cd "$(dirname "$0")" && pwd)/installer/install.sh" "$@"
