#!/bin/sh

awslocal s3api head-bucket --bucket yeodam-performance >/dev/null 2>&1 || \
awslocal s3api create-bucket \
  --bucket yeodam-performance \
  --create-bucket-configuration LocationConstraint=ap-northeast-2