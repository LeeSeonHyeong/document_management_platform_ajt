"""Derived from lucas-llmwiki `mcp/config.py`.

Everything hosted-mode is gone: no Supabase, no S3, no embedding provider, no
Turbopuffer. What is left is the URL used to build the deep links that appear in
tool responses.

The upstream `WORKSPACE_PATH` is gone too. One process serves one repository root
= one scope = one job, so the root has to differ per process and arrives as a CLI
argument (`local_server.py`, `graph_api.py`) rather than from the environment.
"""

from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict

_ENV_FILE = Path(__file__).resolve().parent.parent / ".env"


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=str(_ENV_FILE), extra="ignore")

    # Only used to render "[View](...)" links back to the frontend. The agent
    # never fetches them.
    APP_URL: str = "http://localhost:3000"


settings = Settings()
