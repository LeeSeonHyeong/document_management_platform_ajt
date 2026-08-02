# syntax=docker/dockerfile:1
FROM python:3.12-slim AS base

ARG UV_VERSION=0.8.15
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    UV_LINK_MODE=copy
WORKDIR /app

RUN python -m pip install --no-cache-dir "uv==${UV_VERSION}"
COPY pyproject.toml uv.lock ./

FROM base AS test
RUN uv sync --frozen --extra deepagents --no-install-project
COPY src ./src
COPY tests ./tests
RUN uv sync --frozen --extra deepagents
CMD ["uv", "run", "--no-sync", "pytest", "-m", "not ocr and not llm", "-q"]

FROM base AS runtime-build
RUN uv sync --frozen --no-dev --extra deepagents --no-install-project
COPY src ./src
RUN uv sync --frozen --no-dev --extra deepagents

FROM python:3.12-slim AS runtime
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PATH="/app/.venv/bin:${PATH}" \
    PYTHONPATH=/app/src
WORKDIR /app

RUN groupadd --system --gid 10001 ajt \
    && useradd --system --uid 10001 --gid ajt --home-dir /app \
      --shell /usr/sbin/nologin ajt
COPY --from=runtime-build --chown=ajt:ajt /app/.venv ./.venv
COPY --from=runtime-build --chown=ajt:ajt /app/src ./src
COPY --chown=ajt:ajt LICENSE NOTICE ./

USER ajt
EXPOSE 8000
CMD ["python", "-m", "wiki_api.serve", "--host", "0.0.0.0", "--port", "8000"]
