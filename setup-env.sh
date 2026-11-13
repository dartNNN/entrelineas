#!/usr/bin/env bash
# Creates .venv for the builder, the aligner and the tests. Usage: ./setup-env.sh [--no-align]
set -euo pipefail
cd "$(dirname "$0")"
python3 -m venv .venv
.venv/bin/pip install -q --upgrade pip
if [[ "${1:-}" == "--no-align" ]]; then
  .venv/bin/pip install -q -r builder/requirements.txt
else
  .venv/bin/pip install -q torch==2.15.0 --index-url https://download.pytorch.org/whl/cpu
  .venv/bin/pip install -q -r builder/requirements-align.txt
fi
# The builder's modules on the path of tests and experiments (pyproject.toml), without changing sys.path.
.venv/bin/pip install -q -e . --no-deps
.venv/bin/python -c "import spacy; spacy.load('es_core_news_md'); spacy.load('en_core_web_md'); print('builder ok')"
[[ "${1:-}" == "--no-align" ]] || .venv/bin/python -c "import torch, sentence_transformers, razdel; print('align ok, torch', torch.__version__)"
