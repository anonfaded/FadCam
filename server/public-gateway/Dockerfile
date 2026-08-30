FROM python:3.13-alpine
RUN apk add --no-cache iproute2
COPY discovery.py /app/discovery.py
COPY relay.py /app/relay.py
USER 65532:65532
EXPOSE 18080 18443
ENTRYPOINT ["python","/app/discovery.py"]
