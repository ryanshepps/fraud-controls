FROM python:3.12-slim

WORKDIR /app

COPY fraudgencli/pyproject.toml fraudgencli/README.md ./
COPY fraudgencli/src ./src
COPY fraudgencli/examples ./examples

RUN pip install --no-cache-dir .

ENTRYPOINT ["fraudgen"]
