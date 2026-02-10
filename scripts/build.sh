for dir in $(find . -maxdepth 2 -type f -name Dockerfile -exec dirname {} \;); do
  echo "Building Docker image in directory: $dir"
  IMAGE_NAME=$(basename "$dir")
  docker build -t pair-diag-cn-guangzhou.cr.volces.com/pair/$IMAGE_NAME:v1.2.9 "$dir"
  docker push pair-diag-cn-guangzhou.cr.volces.com/pair/$IMAGE_NAME:v1.2.9
 done
