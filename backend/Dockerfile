FROM python:3.12-slim
RUN pip install --no-cache-dir uv
WORKDIR /app
COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev --no-install-project
COPY . .
# The attestor key arrives as a secret env var (never in the image); devseed refills devnet test pools on a fresh disk.
CMD ["sh", "-c", "printf '%s' \"$ATTESTOR_KEYPAIR_JSON\" > /tmp/attestor.json && export ATTESTOR_KEYPAIR=/tmp/attestor.json ATTEST_PORT=${PORT:-8787} && (uv run --no-dev python -m attest.devseed || true) && exec uv run --no-dev python -m attest.app"]
