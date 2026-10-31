FROM python:3.13-slim
WORKDIR /mock
RUN pip install --no-cache-dir PyJWT==2.15.1 cryptography==50.0.2 \
    && useradd --uid 10001 --create-home mock
COPY server.py .
USER 10001
EXPOSE 8080
CMD ["python", "server.py"]
