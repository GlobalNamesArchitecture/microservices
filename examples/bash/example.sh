#!/bin/bash

# These examples show how to send a query to https://index.globalnames.org
# API using command line tool cURL.

cat  <<EOF

******************************
Example of a hardcoded query
******************************

EOF

query_simple='{ "query":
  "{
  nameResolver(names: [{value: \"Homo sapiens\", suppliedId: \"foo\"}],
               dataSourceIds: [1, 2, 3],
               preferredDataSourceIds: [3, 4],
               bestMatchOnly: false) {
    responses {
      total
      suppliedId
      suppliedInput
      results {
        name {
          id
          value
        }
        canonicalName {
          id
          value
          valueRanked
        }
        dataSource {
          id
          title
        }
        synonym
        taxonId
        matchType {
          kind
          score
          verbatimEditDistance
          stemEditDistance
        }
        vernaculars {
          name
          language
        }
        score {
          nameType
          authorScore {
            authorshipInput
            authorshipMatch
            value
          }
          parsingQuality
          value
        }
      }
      preferredResults {
        name {
          id
          value
        }
        localId
        url
      }
    }
    context {
      clade
      dataSource {
        id
        title
      }
    }
  }
}" }'

echo "
Query:

${query_simple}

"

#remove newlines
query_simple_no_newlines=$(echo ${query_simple} | tr -d "\n" )

echo "
Result:
"

curl \
  -X POST \
  -H "Content-Type: application/json" \
  --data "${query_simple_no_newlines}" \
  https://index.globalnames.org/api/graphql

cat  <<EOF


***********************************
Example of a query with variables
***********************************

EOF


query_with_variables='{ "query": "query($names: [name!]!, $sources: [Int!]!)
{ nameResolver(names: $names, dataSourceIds: $sources)
  { responses {
    total
    results {
      dataSource { id title }
      name {value}
      vernaculars {
        name
        language
      }
    }
  }
} }",
  "variables": { "names": [{ "value": "Plantago major" },
                           { "value": "Pica pica" } ],
                 "sources": [1,4,9,11,179] }
}'

echo "${query_with_variables}"

#remove newlines
query_with_variables_no_newlines=$(echo ${query_with_variables} | tr -d "\n" )

echo "
Result:
"

curl \
  -X POST \
  -H "Content-Type: application/json" \
  --data "${query_with_variables_no_newlines}" \
  https://index.globalnames.org/api/graphql
