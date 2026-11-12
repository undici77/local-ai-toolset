DC := docker compose run --rm build
.PHONY: image wrapper build test verify clean shell sign publish
image:
	docker compose build build
wrapper:
	docker run --rm -u $$(id -u):$$(id -g) -v $$PWD:/src -w /src gradle:9-jdk21 gradle wrapper --gradle-version 9.9.0
build:
	$(DC) ./gradlew --no-daemon buildPlugin
test:
	$(DC) ./gradlew --no-daemon test
verify:
	$(DC) ./gradlew --no-daemon verifyPlugin
clean:
	$(DC) ./gradlew --no-daemon clean
shell:
	$(DC) bash

SECRETS ?= $(HOME)/.secrets/local-ai-toolset
SIGN_ENV = CERTIFICATE_CHAIN="$$(cat $(SECRETS)/certificate-chain.crt)" PRIVATE_KEY="$$(cat $(SECRETS)/private-key.pem)" PRIVATE_KEY_PASSWORD="$$(cat $(SECRETS)/private-key-password.txt)"
sign:
	$(SIGN_ENV) $(DC) ./gradlew --no-daemon signPlugin
publish:
	$(SIGN_ENV) PUBLISH_TOKEN="$$(cat $(SECRETS)/publish-token.txt)" $(DC) ./gradlew --no-daemon test verifyPlugin publishPlugin
