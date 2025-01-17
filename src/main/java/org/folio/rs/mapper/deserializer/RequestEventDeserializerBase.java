package org.folio.rs.mapper.deserializer;

import java.io.IOException;

import org.folio.rs.domain.dto.EventRequest;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;

public abstract class RequestEventDeserializerBase extends StdDeserializer<EventRequest> {

  RequestEventDeserializerBase() {
    this(null);
  }

  RequestEventDeserializerBase(Class<?> vc) {
    super(vc);
  }

  DocumentContext getDocumentContext(JsonParser jsonParser) throws IOException {
    var productNode = jsonParser.getCodec().readTree(jsonParser);
    var conf = Configuration.builder().options(Option.SUPPRESS_EXCEPTIONS).build();
    return JsonPath.using(conf).parse(productNode.toString());
  }
}
