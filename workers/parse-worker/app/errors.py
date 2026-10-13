class ParseFailure(RuntimeError):
    def __init__(self, status_code: int, code: str, message: str):
        super().__init__(message)
        self.status_code = status_code
        self.code = code

    def detail(self):
        return {"code": self.code, "message": str(self)}
