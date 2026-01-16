#!/bin/bash

#
# Copyright (c) 2023-2025 Mariano Barcia
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# Script to generate development certificates for quarkus:dev

# Create a temporary directory for certificate generation
CERT_DIR="/tmp/search-dev-certs"
rm -rf "${CERT_DIR}"
mkdir -p "${CERT_DIR}"

# Create certificate configuration for development
cat > "${CERT_DIR}/cert.conf" <<EOF
[req]
default_bits = 2048
prompt = no
default_md = sha256
distinguished_name = dn
req_extensions = v3_req

[dn]
C = US
ST = CA
L = San Francisco
O = CSV Payments PoC
CN = localhost

[v3_req]
basicConstraints = CA:FALSE
keyUsage = nonRepudiation, digitalSignature, keyEncipherment
subjectAltName = @alt_names

[alt_names]
DNS.1 = localhost
IP.1 = 127.0.0.1
IP.2 = ::1
EOF

# Generate the certificate and key
openssl req -x509 -newkey rsa:2048 -keyout "${CERT_DIR}/quarkus-key.pem" -out "${CERT_DIR}/quarkus-cert.pem" -days 365 -nodes -config "${CERT_DIR}/cert.conf" -extensions v3_req

# Convert to PKCS12 format
openssl pkcs12 -export -in "${CERT_DIR}/quarkus-cert.pem" -inkey "${CERT_DIR}/quarkus-key.pem" -out "${CERT_DIR}/server-keystore.p12" -name server -passout pass:secret

# Create truststore for the orchestrator
keytool -import -file "${CERT_DIR}/quarkus-cert.pem" -keystore "${CERT_DIR}/client-truststore.jks" -storepass secret -noprompt -alias server

# Copy certificates to service directories
for svc in cache-invalidation-svc persistence-csv crawl-source-svc index-documents-svc parse-document-svc tokenize-svc; do
    cp "${CERT_DIR}/server-keystore.p12" "${svc}/src/main/resources/server-keystore.jks"
done

cp "${CERT_DIR}/client-truststore.jks" "orchestrator-svc/src/main/resources/client-truststore.jks"

# Clean up temporary files
rm -rf "${CERT_DIR}"

echo "Development certificates generated successfully."