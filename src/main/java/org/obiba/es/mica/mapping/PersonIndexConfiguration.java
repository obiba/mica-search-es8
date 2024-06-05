/*
 * Copyright (c) 2024 OBiBa. All rights reserved.
 *
 * This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.obiba.es.mica.mapping;

import org.elasticsearch.common.Strings;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentFactory;
import org.obiba.mica.spi.search.ConfigurationProvider;
import org.obiba.mica.spi.search.Indexer;
import org.obiba.mica.spi.search.SearchEngineService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import co.elastic.clients.elasticsearch.indices.PutMappingRequest;

import java.io.IOException;
import java.io.StringReader;

public class PersonIndexConfiguration extends AbstractIndexConfiguration {
  private static final Logger log = LoggerFactory.getLogger(PersonIndexConfiguration.class);

  public PersonIndexConfiguration(ConfigurationProvider configurationProvider) {
    super(configurationProvider);
  }

  @Override
  public void onIndexCreated(SearchEngineService searchEngineService, String indexName) {
    if (Indexer.PERSON_INDEX.equals(indexName)) {
      try {
        XContentBuilder properties = createMappingProperties(Indexer.PERSON_TYPE);

        getClient(searchEngineService)
            .indices()
            .putMapping(
                PutMappingRequest.of(r -> r.index(indexName).withJson(new StringReader(Strings.toString(properties)))));
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
  }

  private XContentBuilder createMappingProperties(String type) throws IOException {
    XContentBuilder mapping = XContentFactory.jsonBuilder().startObject();
    mapping.startObject("properties");
    mapping.startObject("id").field("type", "keyword").endObject();
    mapping.startObject("studyMemberships").startObject("properties");
    mapping.startObject("parentId").field("type", "keyword").endObject();
    mapping.startObject("role").field("type", "keyword").endObject();
    mapping.endObject().endObject();
    mapping.startObject("networkMemberships").startObject("properties");
    mapping.startObject("parentId").field("type", "keyword").endObject();
    mapping.startObject("role").field("type", "keyword").endObject();
    mapping.endObject().endObject();
    mapping.startObject("institution");
    mapping.startObject("properties");
    createLocalizedMappingWithAnalyzers(mapping, "name");
    mapping.endObject().endObject();

    createMappingWithAndWithoutAnalyzer(mapping, "firstName");
    createMappingWithAndWithoutAnalyzer(mapping, "lastName");
    createMappingWithAndWithoutAnalyzer(mapping, "fullName");
    createMappingWithAndWithoutAnalyzer(mapping, "email");
    mapping.endObject(); // properties
    mapping.endObject();

    return mapping;
  }
}
