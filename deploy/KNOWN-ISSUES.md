# Known issues for local kind integration

No current deploy/runtime issues are known for the local kind integration
environment.

The 23-service stack is expected to expose the contract probe endpoints used by
`deploy/k8s`, including `/healthz` and `/readyz`, and to start with the image
entrypoints built by `deploy/build-images.sh`. Keep this file limited to active
operator-facing issues; resolved smoke-run notes and build implementation
details belong in git history or the deployment README, not here.
