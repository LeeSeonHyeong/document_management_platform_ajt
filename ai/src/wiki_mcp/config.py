"""Derived from lucas-llmwiki `mcp/config.py`.

Everything hosted-mode is gone: no Supabase, no S3, no embedding provider, no
Turbopuffer. What is left is the workspace path and the URL used to build the
deep links that appear in tool responses.
"""

from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict

_ENV_FILE = Path(__file__).resolve().parent.parent / ".env"


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=str(_ENV_FILE), extra="ignore")

    # Root holding sources/, wiki/ and .llmwiki/index.db. One workspace per
    # server process, which is also one scope — see vaultfs.sqlite.
    WORKSPACE_PATH: str = "."

    # Only used to render "[View](...)" links back to the frontend. The agent
    # never fetches them.
    APP_URL: str = "http://localhost:3000"


settings = Settings()
