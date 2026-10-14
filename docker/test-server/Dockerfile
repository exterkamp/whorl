# Throwaway sshd + mosh-server for the integration test. Password login for user "test" is
# only so the test can install the key it generates; nothing else should ever use this image.
FROM debian:bookworm-slim
RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
      openssh-server mosh locales \
    && sed -i 's/^# *en_US.UTF-8/en_US.UTF-8/' /etc/locale.gen && locale-gen \
    && rm -rf /var/lib/apt/lists/* \
    && useradd -m -s /bin/bash test && echo 'test:test' | chpasswd \
    && mkdir -p /run/sshd
CMD ["/usr/sbin/sshd", "-D", "-e"]
