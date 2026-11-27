import asyncio
import logging


class FirebasePush:
    def __init__(self, project):
        import google.auth
        from google.auth.transport.requests import AuthorizedSession
        credentials, _ = google.auth.default(scopes=["https://www.googleapis.com/auth/firebase.messaging"])
        self.session = AuthorizedSession(credentials)
        self.url = f"https://fcm.googleapis.com/v1/projects/{project}/messages:send"

    async def __call__(self, token, call_id):
        return await asyncio.to_thread(self._send, token, call_id)

    def _send(self, token, call_id):
        try:
            response = self.session.post(self.url, json={"message": {
                "token": token,
                "notification": {"title": "Wi-Call", "body": "لديك مكالمة فائتة. افتح Wi-Call لعرض الرقم."},
                "data": {"wi_missed": "1"},
                "android": {"priority": "high", "ttl": "604800s", "notification": {"tag": call_id}}
            }}, timeout=4)
            return 200 <= response.status_code < 300
        except OSError:
            logging.warning("Missed-call push transport unavailable")
            return False
