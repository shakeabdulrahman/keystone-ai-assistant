# Local secrets

Files here are mounted read-only into the API container at `/run/secrets/keystone/`. Git ignores this folder (only this README is tracked).

- `firebase-service-account.json`: the Firebase key that lets the backend send push notifications. See `docs/firebase-setup.md`, section "Push notifications".

Never commit these files, paste them into chat, or bake them into the Docker image.
