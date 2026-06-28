# syntax=docker/dockerfile:1.6
ARG DEBIAN_TAG=trixie-slim
FROM eclipse-temurin:17-jdk AS jdk17

FROM debian:${DEBIAN_TAG}

ARG NODE_MAJOR=20
ARG HELM_VERSION=v3.15.4
ARG KUBECTL_VERSION=v1.30.4
ARG SKAFFOLD_VERSION=v2.13.2
ARG YQ_VERSION=v4.44.3
ARG USERNAME=vscode
ARG USER_UID=1000
ARG USER_GID=${USER_UID}

COPY --from=jdk17 /opt/java/openjdk /opt/java/openjdk

ENV DEBIAN_FRONTEND=noninteractive \
    JAVA_HOME=/opt/java/openjdk \
    MAVEN_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0" \
    NPM_CONFIG_REGISTRY=https://registry.npmmirror.com \
    PATH="/opt/java/openjdk/bin:${PATH}" \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PIP_INDEX_URL=https://pypi.tuna.tsinghua.edu.cn/simple \
    PYTHONUNBUFFERED=1 \
    TZ=Asia/Shanghai

RUN ln -snf /usr/share/zoneinfo/${TZ} /etc/localtime \
    && echo "${TZ}" > /etc/timezone

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        apt-transport-https \
        bash-completion \
        build-essential \
        ca-certificates \
        cmake \
        curl \
        file \
        git \
        git-lfs \
        gnupg \
        jq \
        less \
        libgl1 \
        libglib2.0-0 \
        liblapack-dev \
        libopenblas-dev \
        libsm6 \
        libxext6 \
        libxrender1 \
        lsb-release \
        make \
        maven \
        nano \
        openssh-client \
        pkg-config \
        procps \
        python3 \
        python3-dev \
        python3-pip \
        python3-setuptools \
        python3-venv \
        python3-wheel \
        rsync \
        sudo \
        unzip \
        vim \
        zip \
    && git lfs install --system \
    && rm -rf /var/lib/apt/lists/*

RUN set -eux; \
    for tool in java javac jar jarsigner javadoc javap jcmd jconsole jdb jdeprscan jdeps jfr jhsdb jimage jinfo jlink jmap jmod jpackage jps jrunscript jshell jstack jstat jstatd keytool rmiregistry serialver; do \
        if [ -x "${JAVA_HOME}/bin/${tool}" ]; then \
            update-alternatives --install "/usr/bin/${tool}" "${tool}" "${JAVA_HOME}/bin/${tool}" 20000; \
            update-alternatives --set "${tool}" "${JAVA_HOME}/bin/${tool}"; \
        fi; \
    done

RUN install -m 0755 -d /etc/apt/keyrings \
    && curl -fsSL https://download.docker.com/linux/debian/gpg | gpg --dearmor -o /etc/apt/keyrings/docker.gpg \
    && chmod a+r /etc/apt/keyrings/docker.gpg \
    && . /etc/os-release \
    && echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/debian ${VERSION_CODENAME} stable" > /etc/apt/sources.list.d/docker.list \
    && curl -fsSL https://deb.nodesource.com/gpgkey/nodesource-repo.gpg.key | gpg --dearmor -o /etc/apt/keyrings/nodesource.gpg \
    && echo "deb [signed-by=/etc/apt/keyrings/nodesource.gpg] https://deb.nodesource.com/node_${NODE_MAJOR}.x nodistro main" > /etc/apt/sources.list.d/nodesource.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends \
        docker-buildx-plugin \
        docker-ce-cli \
        docker-compose-plugin \
        nodejs \
    && npm config set registry https://registry.npmmirror.com \
    && rm -rf /var/lib/apt/lists/*

RUN set -eux; \
    arch="$(dpkg --print-architecture)"; \
    case "${arch}" in \
        amd64) tool_arch="amd64" ;; \
        arm64) tool_arch="arm64" ;; \
        *) echo "unsupported architecture: ${arch}" >&2; exit 1 ;; \
    esac; \
    curl -fsSL -o /usr/local/bin/kubectl "https://dl.k8s.io/release/${KUBECTL_VERSION}/bin/linux/${tool_arch}/kubectl"; \
    chmod +x /usr/local/bin/kubectl; \
    curl -fsSL -o /tmp/helm.tar.gz "https://get.helm.sh/helm-${HELM_VERSION}-linux-${tool_arch}.tar.gz"; \
    tar -xzf /tmp/helm.tar.gz -C /tmp; \
    mv "/tmp/linux-${tool_arch}/helm" /usr/local/bin/helm; \
    curl -fsSL -o /usr/local/bin/skaffold "https://storage.googleapis.com/skaffold/releases/${SKAFFOLD_VERSION}/skaffold-linux-${tool_arch}"; \
    chmod +x /usr/local/bin/skaffold; \
    curl -fsSL -o /usr/local/bin/yq "https://github.com/mikefarah/yq/releases/download/${YQ_VERSION}/yq_linux_${tool_arch}"; \
    chmod +x /usr/local/bin/yq; \
    rm -rf /tmp/helm.tar.gz "/tmp/linux-${tool_arch}"

RUN set -eux; \
    groupadd --gid "${USER_GID}" "${USERNAME}"; \
    useradd --uid "${USER_UID}" --gid "${USER_GID}" -m -s /bin/bash "${USERNAME}"; \
    groupadd -f docker; \
    usermod -aG docker "${USERNAME}"; \
    echo "${USERNAME} ALL=(ALL) NOPASSWD:ALL" > "/etc/sudoers.d/${USERNAME}"; \
    chmod 0440 "/etc/sudoers.d/${USERNAME}"; \
    mkdir -p "/home/${USERNAME}/.m2" "/home/${USERNAME}/.cache" "/home/${USERNAME}/.skaffold" "/home/${USERNAME}/.npm"; \
    chown -R "${USERNAME}:${USERNAME}" "/home/${USERNAME}"

RUN curl -LsSf https://astral.sh/uv/install.sh | env UV_INSTALL_DIR=/usr/local/bin sh \
    && pip3 config set global.index-url "${PIP_INDEX_URL}" \
    && pip3 config set global.timeout 120

WORKDIR /workspace/train-ticket
USER ${USERNAME}
