/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import io.milvus.client.*;
import io.milvus.client.dsl.MilvusService;
import io.milvus.client.dsl.Query;
import io.milvus.client.dsl.Schema;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.stream.Collectors;
import java.util.stream.DoubleStream;
import org.json.JSONObject;

/**
 * This is an example of Milvus Java SDK v0.9.2. In particular, we demonstrate how we can build and
 * search by index in Milvus.
 *
 * <p>We will be using `films.csv` as our dataset. There are 4 columns in the file, namely `id`,
 * `title`, `release_year` and `embedding`. The dataset comes from MovieLens `ml-latest-small`, with
 * id and embedding being fake values.
 *
 * <p>We assume that you have walked through `MilvusBasicExample.java` and understand basic
 * operations in Milvus. For detailed API documentation, please refer to
 * https://milvus-io.github.io/milvus-sdk-java/javadoc/io/milvus/client/package-summary.html
 */
public class MilvusIndexExample {

  public static final int dimension = 8;

  // Helper function that generates random float vectors
  private static List<List<Float>> randomFloatVectors(int vectorCount, int dimension) {
    SplittableRandom splitCollectionRandom = new SplittableRandom();
    List<List<Float>> vectors = new ArrayList<>(vectorCount);
    for (int i = 0; i < vectorCount; ++i) {
      splitCollectionRandom = splitCollectionRandom.split();
      DoubleStream doubleStream = splitCollectionRandom.doubles(dimension);
      List<Float> vector =
          doubleStream.boxed().map(Double::floatValue).collect(Collectors.toList());
      vectors.add(vector);
    }
    return vectors;
  }

  public static void main(String[] args) {
    try {
      run();
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  public static void run() throws IOException {

    // Connect to Milvus
    ConnectParam connectParam = new ConnectParam.Builder().build();
    MilvusClient client = new MilvusGrpcClient(connectParam);

    /*
     * Basic create collection:
     *   Another way to create a collection is to predefine a schema. Then we can use a
     *   MilvusService to wrap the schema, collection name and Milvus client. The service
     *   is intended to simplify API calls.
     */
    final String collectionName = "demo_index";
    FilmSchema filmSchema = new FilmSchema();
    MilvusService service = new MilvusService(client, collectionName, filmSchema);

    if (service.listCollections().contains(collectionName)) {
      service.dropCollection();
    }

    service.createCollection(
        new JsonBuilder().param("auto_id", false).param("segment_row_limit", 4096).build());

    /*
     * Basic insert and create index:
     *   Now that we have a collection in Milvus, we can create an index for `embedding`.
     *
     *   We can create index BEFORE or AFTER we insert any entities. However, Milvus will not
     *   actually start to build the index until the number of rows you insert reaches `segment_row_limit`.
     *
     *   We will read data from `films.csv` and again, group data with the same field together.
     */
    String path = System.getProperty("user.dir") + "/src/main/java/films.csv";
    BufferedReader csvReader = new BufferedReader(new FileReader(path));
    List<Long> ids = new ArrayList<>();
    List<String> titles = new ArrayList<>();
    List<Integer> releaseYears = new ArrayList<>();
    List<List<Float>> embeddings = new ArrayList<>();
    String row;
    while ((row = csvReader.readLine()) != null) {
      String[] data = row.split(",");
      // process four columns in order
      ids.add(Long.parseLong(data[0]));
      titles.add(data[1]);
      releaseYears.add(Integer.parseInt(data[2]));
      List<Float> embedding = new ArrayList<>(dimension);
      for (int i = 3; i < 11; i++) {
        // 8 float values in a vector
        if (i == 3) {
          embedding.add(Float.parseFloat(data[i].substring(2)));
        } else if (i == 10) {
          embedding.add(Float.parseFloat(data[i].substring(1, data[i].length() - 2)));
        } else {
          embedding.add(Float.parseFloat(data[i].substring(1)));
        }
      }
      embeddings.add(embedding);
    }
    csvReader.close();

    // Now we can insert entities, the total row count should be 8657.
    service.insert(
        insertParam ->
            insertParam
                .withIds(ids)
                .with(filmSchema.releaseYear, releaseYears)
                .with(filmSchema.embedding, embeddings));
    service.flush();
    System.out.printf("There are %d films in the collection.\n", service.countEntities());

    /*
     * Basic create index:
     *   When building index, we need to indicate which field to build index for, the `index_type`,
     *   `metric_type` and params for the specific index type. Here we create an `IVF_FLAT` index
     *   with param `nlist`. Refer to Milvus documentation for more information on choosing
     *   parameters when creating index.
     *
     *   Note that if there is already an index and create index is called again, the previous index
     *   will be replaced.
     *
     *   We present two ways to create an index: using Index or MilvusService.
     */
    // Approach one
    Index index =
        Index.create(collectionName, "embedding")
            .setIndexType(IndexType.IVF_FLAT)
            .setMetricType(MetricType.L2)
            .setParamsInJson(new JsonBuilder().param("nlist", 100).build());
    client.createIndex(index);

    // Approach two
    service.createIndex(
        filmSchema.embedding,
        IndexType.IVF_FLAT,
        MetricType.L2,
        new JsonBuilder().param("nlist", 100).build());

    // Get collection stats with index
    System.out.println("\n--------Collection Stats--------");
    JSONObject json = new JSONObject(service.getCollectionStats());
    System.out.println(json.toString(4));

    /*
     * Basic search entities:
     *   In order to search with index, specific search parameters need to be provided. For `IVF_FLAT`,
     *   thr param is `nprobe`.
     *
     *   Based on the index you created, the available search parameters will be different. Refer to
     *   Milvus documentation for how to set the optimal parameters based on your needs.
     *
     *   Here we present a way to use predefined schema to search vectors.
     */
    List<List<Float>> queryEmbedding = randomFloatVectors(1, dimension);
    final int topK = 3;
    Query query =
        Query.bool(
            Query.must(
                filmSchema.releaseYear.in(1995, 2002),
                filmSchema.embedding.query(queryEmbedding)
                    .metricType(MetricType.L2)
                    .top(topK)
                    .param("nprobe", 8)));
    SearchParam searchParam =
        service
            .buildSearchParam(query)
            .setParamsInJson("{\"fields\": [\"release_year\", \"embedding\"]}");
    System.out.println("\n--------Search Result--------");
    SearchResult searchResult = service.search(searchParam);
    System.out.println("- ids: " + searchResult.getResultIdsList().toString());
    System.out.println("- distances: " + searchResult.getResultDistancesList().toString());
    for (List<Map<String, Object>> singleQueryResult : searchResult.getFieldsMap()) {
      for (int i = 0; i < singleQueryResult.size(); i++) {
        Map<String, Object> res = singleQueryResult.get(i);
        System.out.println("==");
        System.out.println(
            "- title: "
                + titles.get(Math.toIntExact(searchResult.getResultIdsList().get(0).get(i))));
        System.out.println("- release_year: " + res.get("release_year"));
        System.out.println("- embedding: " + res.get("embedding"));
      }
    }

    /*
     * Basic delete index:
     *   Index can be dropped for a vector field.
     */
    service.dropIndex(filmSchema.embedding);

    if (service.listCollections().contains(collectionName)) {
      service.dropCollection();
    }

    // Close connection
    service.close();
  }

  // Schema that defines a collection
  private static class FilmSchema extends Schema {
    public final Int32Field releaseYear = new Int32Field("release_year");
    public final FloatVectorField embedding = new FloatVectorField("embedding", dimension);
  }
}
