FROM postgres:17.11-alpine

RUN apk add --no-cache age aws-cli coreutils

COPY backup.sh /backup.sh
RUN chmod 0555 /backup.sh

USER postgres
ENTRYPOINT ["/backup.sh"]
