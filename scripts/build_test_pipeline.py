"""Builds a Logstash test pipeline from deploy/logstash/pipeline/flow.conf (standard library only).

The filter section (between "# BEGIN FILTER" and "# END FILTER") is copied verbatim and wrapped
with the given input and output, so tests always exercise the production filters.

usage: build_test_pipeline.py <stdin-file|stdin-es|beats-file> <out.conf> [es-index]
"""
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SOURCE = ROOT / "deploy/logstash/pipeline/flow.conf"

INPUTS = {
    "stdin": 'input {\n  stdin { codec => json_lines }\n}\n',
    "beats": 'input {\n  beats { port => 5044 }\n}\n',
}
# File outputs also keep the document ID (normally only in @metadata) for fixture building.
FILE_OUTPUT = (
    'filter {\n  mutate { copy => { "[@metadata][doc_id]" => "test_doc_id" } }\n}\n\n'
    'output {\n  file { path => "/out/actual.jsonl" codec => json_lines }\n}\n'
)
ES_OUTPUT = '''output {
  elasticsearch {
    hosts => ["http://es:9200"]
    index => "%s"
    document_id => "%%{[@metadata][doc_id]}"
    action => "create"
    manage_template => false
    ilm_enabled => false
    data_stream => false
  }
}
'''


def main(kind, out, index="parse010"):
    text = SOURCE.read_text(encoding="utf-8")
    start = text.index("# BEGIN FILTER")
    end = text.index("# END FILTER") + len("# END FILTER")
    source, sink = kind.split("-")
    output = FILE_OUTPUT if sink == "file" else ES_OUTPUT % index
    pathlib.Path(out).write_text(INPUTS[source] + "\n" + text[start:end] + "\n\n" + output, encoding="utf-8")


if __name__ == "__main__":
    main(*sys.argv[1:])
