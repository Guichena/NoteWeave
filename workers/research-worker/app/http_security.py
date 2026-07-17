from __future__ import annotations

import urllib.request


class NoCredentialRedirectHandler(urllib.request.HTTPRedirectHandler):
    """Fail closed instead of replaying provider credentials to a redirect."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):  # noqa: ANN001
        del req, fp, code, msg, headers, newurl
        return None


def credential_safe_urlopen(request: urllib.request.Request, *, timeout: float):
    opener = urllib.request.build_opener(NoCredentialRedirectHandler())
    return opener.open(request, timeout=timeout)
