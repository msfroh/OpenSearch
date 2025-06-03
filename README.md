## Running OpenSearch with etcd cluster state

Within this branch, I'm updating the README to explain how to get started with etcd.

### Install and launch etcd

See instructions at https://etcd.io/docs/v3.5/install/.

On my Mac, this meant `brew install etcd`. Then I just ran the `etcd` executable and left it running in a terminal tab. 



On Ubuntu, I needed to run `sudo apt install etcd-server etcd-client` to get both the `etcd` and `etcdctl` commands. Installing the server automatically started the `etcd` process.

You should also get the `etcdctl` command line tool included. You can interact with the running local etcd instance as follows:

```bash
# Write value 'bar' to key 'foo'
% etcdctl put foo bar
OK

# Read the value from key 'foo'
% etcdctl get foo
foo
bar

# Get all keys whose first byte is between ' ' (the earliest printable character) and '~' (the last)
%  etcdctl get ' ' '~'
foo
bar

# Delete the entry for key 'foo'
% etcdctl del foo
1
```

### Run two OpenSearch nodes from this branch

```bash
# Clone the repo
% git clone https://github.com/msfroh/OpenSearch.git

# Enter the cloned repo
% cd OpenSearch

# Checkout the correct branch
% git checkout clusterless_datanode

# Run with the cluster-etcd plugin loaded and launch two nodes
% ./gradlew run -PinstalledPlugins="['cluster-etcd']" -PnumNodes=2

# In another tab, check the local cluster state for each node
# In the examples below, this will be the data node
% curl 'http://localhost:9200/_cluster/state?local&pretty'

# In the examples below, this will be the coordinator node
% curl 'http://localhost:9201/_cluster/state?local&pretty'
```

### Push some state to etcd to start a data node

```bash
# Write some index metadata for an index. For now, this is the smallest valid metadata I've been able to create.
% cat << EOF | etcdctl put myindex 
{
  "myindex": {
    "version":1,
    "mapping_version":1,
    "settings_version":1,
    "aliases_version":1,
    "state":"open",
    "settings":{
      "index":{
        "number_of_shards":"1",
        "number_of_replicas":"0",
        "uuid":"E8F2-ebqQ1-U4SL6NoPEyw",
        "version": {
          "created":"137227827"
        }
      }
    },
    "mappings":{
      "_doc":{
        "properties":{
          "title":{
            "type":"text",
            "fields":{
              "keyword":{
                "type":"keyword",
                "ignore_above":256
              }
            }
          }
        }
      }
    },
    "primary_terms":[1]
  }
}
EOF

# Assign primary for shard 0 of myindex to the node listening on port 9200/9300
% etcdctl put '127.0.0.1:9300' '{"local_shards":{"myindex":{"0":"PRIMARY"}}}'

# Check the local cluster state
% curl 'http://localhost:9200/_cluster/state?local&pretty'

# Write a document
% curl -X POST -H 'Content-Type: application/json' http://localhost:9200/myindex/_doc/1 -d '{"title":"Hello"}'

# Search the document
% curl 'http://localhost:9200/myindex/_search?pretty'
```

### Add a coordinator

In order for the coordinator node to complete a successful handshake with the data node, they must agree on the
data node's persistent id and ephemeral_id, which are both generated on startup.

```bash
# Get the node ID and ephemeral ID from the data node. (These were generated on startup.)
% DATA_NODE_ID=$(curl 'http://localhost:9200/_cluster/state?local' | jq -r '.nodes | keys[0]' )

% DATA_NODE_EPHEMERAL_ID=$(curl 'http://localhost:9200/_cluster/state?local' | jq -r ".nodes.[\"${DATA_NODE_ID}\"].ephemeral_id")

# Tell the coordinator that shard 0 of myindex is found on the data node
% cat << EOF | etcdctl put 127.0.0.1:9301
{
  "remote_shards": {
    "myindex": {
      "uuid" : "E8F2-ebqQ1-U4SL6NoPEyw",
      "shard_routing" : [
        [
          {
            "node_id": "${DATA_NODE_ID}",       
            "ephemeral_id": "${DATA_NODE_EPHEMERAL_ID}",
            "address": "127.0.0.1",
            "port": 9300
          }
        ]
      ]
    }
  }
}
EOF 

# Search via the coordinator node
% curl 'http://localhost:9201/myindex/_search?pretty'
```

<img src="https://opensearch.org/assets/img/opensearch-logo-themed.svg" height="64px">

[![Chat](https://img.shields.io/badge/chat-on%20forums-blue)](https://forum.opensearch.org/c/opensearch/)
[![Documentation](https://img.shields.io/badge/documentation-reference-blue)](https://opensearch.org/docs/latest/opensearch/index/)
[![Code Coverage](https://codecov.io/gh/opensearch-project/OpenSearch/branch/main/graph/badge.svg)](https://codecov.io/gh/opensearch-project/OpenSearch)
[![Untriaged Issues](https://img.shields.io/github/issues/opensearch-project/OpenSearch/untriaged?labelColor=red)](https://github.com/opensearch-project/OpenSearch/issues?q=is%3Aissue+is%3Aopen+label%3A"untriaged")
[![Security Vulnerabilities](https://img.shields.io/github/issues/opensearch-project/OpenSearch/security%20vulnerability?labelColor=red)](https://github.com/opensearch-project/OpenSearch/issues?q=is%3Aissue+is%3Aopen+label%3A"security%20vulnerability")
[![Open Issues](https://img.shields.io/github/issues/opensearch-project/OpenSearch)](https://github.com/opensearch-project/OpenSearch/issues)
[![Open Pull Requests](https://img.shields.io/github/issues-pr/opensearch-project/OpenSearch)](https://github.com/opensearch-project/OpenSearch/pulls)
[![2.19.3 Open Issues](https://img.shields.io/github/issues/opensearch-project/OpenSearch/v2.19.3)](https://github.com/opensearch-project/OpenSearch/issues?q=is%3Aissue+is%3Aopen+label%3A"v2.19.3")
[![2.18.1 Open Issues](https://img.shields.io/github/issues/opensearch-project/OpenSearch/v2.18.1)](https://github.com/opensearch-project/OpenSearch/issues?q=is%3Aissue+is%3Aopen+label%3A"v2.18.1")
[![3.0.0 Open Issues](https://img.shields.io/github/issues/opensearch-project/OpenSearch/v3.0.0)](https://github.com/opensearch-project/OpenSearch/issues?q=is%3Aissue+is%3Aopen+label%3A"v3.0.0")
[![GHA gradle check](https://github.com/opensearch-project/OpenSearch/actions/workflows/gradle-check.yml/badge.svg)](https://github.com/opensearch-project/OpenSearch/actions/workflows/gradle-check.yml)
[![GHA validate pull request](https://github.com/opensearch-project/OpenSearch/actions/workflows/wrapper.yml/badge.svg)](https://github.com/opensearch-project/OpenSearch/actions/workflows/wrapper.yml)
[![GHA precommit](https://github.com/opensearch-project/OpenSearch/actions/workflows/precommit.yml/badge.svg)](https://github.com/opensearch-project/OpenSearch/actions/workflows/precommit.yml)
[![Jenkins gradle check job](https://img.shields.io/jenkins/build?jobUrl=https%3A%2F%2Fbuild.ci.opensearch.org%2Fjob%2Fgradle-check%2F&label=Jenkins%20Gradle%20Check)](https://build.ci.opensearch.org/job/gradle-check/)

- [Welcome!](#welcome)
- [Project Resources](#project-resources)
- [Code of Conduct](#code-of-conduct)
- [Security](#security)
- [License](#license)
- [Copyright](#copyright)
- [Trademark](#trademark)

## Welcome!

**OpenSearch** is [a community-driven, open source fork](https://aws.amazon.com/blogs/opensource/introducing-opensearch/) of [Elasticsearch](https://en.wikipedia.org/wiki/Elasticsearch) and [Kibana](https://en.wikipedia.org/wiki/Kibana) following the [license change](https://blog.opensource.org/the-sspl-is-not-an-open-source-license/) in early 2021. We're looking to sustain (and evolve!) a search and analytics suite for the multitude of businesses who are dependent on the rights granted by the original, [Apache v2.0 License](LICENSE.txt).

## Project Resources

* [Project Website](https://opensearch.org/)
* [Downloads](https://opensearch.org/downloads.html)
* [Documentation](https://opensearch.org/docs/)
* Need help? Try [Forums](https://discuss.opendistrocommunity.dev/)
* [Project Principles](https://opensearch.org/#principles)
* [Contributing to OpenSearch](CONTRIBUTING.md)
* [Maintainer Responsibilities](MAINTAINERS.md)
* [Release Management](RELEASING.md)
* [Admin Responsibilities](ADMINS.md)
* [Testing](TESTING.md)
* [Security](SECURITY.md)

## Code of Conduct

The project's [Code of Conduct](CODE_OF_CONDUCT.md) outlines our expectations for all participants in our community, based on the [OpenSearch Code of Conduct](https://opensearch.org/code-of-conduct/). Please contact [conduct@opensearch.foundation](mailto:conduct@opensearch.foundation) with any additional questions or comments.

## Security

If you discover a potential security issue in this project we ask that you notify OpenSearch Security directly via email to security@opensearch.org. Please do **not** create a public GitHub issue.

## License

This project is licensed under the [Apache v2.0 License](LICENSE.txt).

## Copyright

Copyright OpenSearch Contributors. See [NOTICE](NOTICE.txt) for details.

## Trademark

OpenSearch is a registered trademark of Amazon Web Services.

OpenSearch includes certain Apache-licensed Elasticsearch code from Elasticsearch B.V. and other source code. Elasticsearch B.V. is not the source of that other source code. ELASTICSEARCH is a registered trademark of Elasticsearch B.V.
