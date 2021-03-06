# PNC Groovy Client

A minimal [Newcastle](https://github.com/project-ncl/pnc) REST API Client written in Groovy.

## Overview

### Features

- Always has the latest operations (swagger API data processed at runtime)
- Read-only queries do not require authentication
- Transparent pagination (pages are detected, fetched, and unpacked for you)
- Authentication
- Optional CLI
  - [RPM available](https://copr.devel.redhat.com/coprs/aszczucz/pnc-groovy-client/) with pre-written config file
  - debugging
  - shell scripts
  - discovering new API operations
  - allows multiple PNC instances to be accessed from one config file (using `--config-prefix`)
  - generally conforms to POSIX CLI conventions

### TODO

- Body parameters (used for some non-idempotent operations)
  - Model mapping
  - Construction arguments
- First release to maven central

## Usage

### CLI

```bash
alias pgc='java -jar cli/target/pnc-groovy-cli-1.0.0-SNAPSHOT-executable.jar'
echo 'pnc.url=http://orch.example.com/pnc-rest/rest/swagger.json' > $HOME/.config/pgc.properties
pgc -h
pgc list -h
pgc call -h
pnc_version="$(pgc call build-records get-specific -a id=7113 | jq -r '.executionRootVersion')"
```

### API

```groovy
def pnc = new PncClient('http://orch.example.com/pnc-rest/rest/swagger.json')
Map buildInfo = pnc.exec(
    'buildRecords', 'getSpecific',
    id: 7113,
)
String pncVersion = buildInfo['executionRootVersion']
```

## Development

### Compile

```bash
mvn clean package
```

#### Unit Tests

Unit tests are executed by default during compilation

### Integration Tests

```bash
mvn clean package -Dintegration.pnc.host=pnc-orch-master-nightly.cloud.pnc.devel.example.com
```
