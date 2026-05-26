FROM python:3.12-slim

WORKDIR /app
COPY src ./src

ENV PYTHONPATH=/app/src
ENV PORT=50051

EXPOSE 50051

CMD ["python", "-m", "scoring_sidecar.demo_server"]
