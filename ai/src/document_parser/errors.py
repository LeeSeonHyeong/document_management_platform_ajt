"""Exceptions raised by document parsers."""


class DocumentParseFailure(Exception):
    def __init__(
        self, code: str, message: str, failed_pages: tuple[int, ...] = ()
    ) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.failed_pages = failed_pages
