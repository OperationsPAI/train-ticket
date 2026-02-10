#!/bin/bash
set -e

CHART_DIR="manifests/helm/trainticket"

if [ -z "$GITHUB_REPOSITORY" ]; then
    GIT_REMOTE=$(git config --get remote.origin.url 2>/dev/null || echo "")
    if [[ $GIT_REMOTE =~ github.com[:/]([^/]+)/([^/.]+) ]]; then
        ORG_NAME="${BASH_REMATCH[1]}"
        REPO_NAME="${BASH_REMATCH[2]}"
    else
        ORG_NAME="your-org-name"
        REPO_NAME="train-ticket"
        echo "Warning: Using default ORG_NAME and REPO_NAME"
    fi
else
    ORG_NAME=$(echo $GITHUB_REPOSITORY | cut -d'/' -f1 | tr '[:upper:]' '[:lower:]')
    REPO_NAME=$(echo $GITHUB_REPOSITORY | cut -d'/' -f2 | tr '[:upper:]' '[:lower:]')
fi

REPO_URL="https://${ORG_NAME}.github.io/${REPO_NAME}"

echo "Target Repo URL: $REPO_URL"

if ! command -v yq &> /dev/null; then
    echo "yq not found, installing..."
    YQ_VERSION="v4.44.1"
    YQ_BINARY="yq_linux_amd64"
    wget -qO /tmp/yq "https://github.com/mikefarah/yq/releases/download/${YQ_VERSION}/${YQ_BINARY}"
    chmod +x /tmp/yq

    if sudo -n mv /tmp/yq /usr/local/bin/yq 2>/dev/null; then
        echo "yq installed to /usr/local/bin/yq"
    else
        mv /tmp/yq ./yq
        export PATH="$PWD:$PATH"
        echo "yq installed to current directory"
    fi
    echo "yq installed successfully"
fi

yq eval ".dependencies[] |= select(.name == \"loadgenerator\").repository = \"https://${ORG_NAME}.github.io/loadgenerator\"" -i $CHART_DIR/Chart.yaml

helm dependency update $CHART_DIR

mkdir -p .deploy
helm package $CHART_DIR -d .deploy

cd .deploy
if [ -f index.yaml ]; then
    helm repo index . --url $REPO_URL --merge index.yaml
else
    helm repo index . --url $REPO_URL
fi
cd ..