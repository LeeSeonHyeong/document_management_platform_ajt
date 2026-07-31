from .base import (
    INDEX_ADDRESS,
    PAGES_PREFIX,
    SOURCES_PREFIX,
    ReadOnlyLayerError,
    VaultError,
    VaultFS,
)
from .local import LocalVaultFS, kind_for
from .spring import SpringVaultFS, address_from_wiki_path  # noqa: E402,F401
from .federated import FederatedVaultFS  # noqa: E402,F401

# v1.1.0: pull 경로 제거 완료 (Task 4) — `spring.py`는 `pages=`/`index_markdown=`으로만
# 채워지고, `springclient.py`(SpringClient/SpringNotFound/SpringUnavailable)는 지워졌다.

__all__ = [
    "VaultFS",
    "VaultError",
    "ReadOnlyLayerError",
    "LocalVaultFS",
    "kind_for",
    "INDEX_ADDRESS",
    "PAGES_PREFIX",
    "SOURCES_PREFIX",
    "SpringVaultFS",
    "FederatedVaultFS",
    "address_from_wiki_path",
]
