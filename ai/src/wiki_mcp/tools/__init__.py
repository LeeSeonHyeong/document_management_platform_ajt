"""Derived from lucas-llmwiki `mcp/tools/__init__.py`.

`register` takes `get_scope_key` and `fs_factory` rather than importing a store,
so the same tools run against the local work space now and a Spring adapter later.
"""


def register(mcp, get_scope_key, fs_factory) -> None:
    from .delete import register as register_delete
    from .guide import register as register_guide
    from .lint import register as register_lint
    from .read import register as register_read
    from .scope import register as register_scope
    from .search import register as register_search
    from .write import register as register_write

    register_guide(mcp, get_scope_key, fs_factory)
    register_scope(mcp, get_scope_key, fs_factory)
    register_search(mcp, get_scope_key, fs_factory)
    register_read(mcp, get_scope_key, fs_factory)
    register_write(mcp, get_scope_key, fs_factory)
    register_delete(mcp, get_scope_key, fs_factory)
    register_lint(mcp, get_scope_key, fs_factory)
