/*
 * Copyright 2010-2012 Luca Garulli (l.garulli--at--orientechnologies.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.orientechnologies.orient.graph.sql;

import com.orientechnologies.orient.core.command.OCommandRequest;
import com.orientechnologies.orient.core.command.OCommandRequestText;
import com.orientechnologies.orient.core.command.OCommandResultListener;
import com.orientechnologies.orient.core.db.record.OIdentifiable;
import com.orientechnologies.orient.core.exception.OCommandExecutionException;
import com.orientechnologies.orient.core.exception.OConcurrentModificationException;
import com.orientechnologies.orient.core.id.ORecordId;
import com.orientechnologies.orient.core.metadata.schema.OClass;
import com.orientechnologies.orient.core.record.impl.ODocument;
import com.orientechnologies.orient.core.sql.OCommandExecutorSQLRetryAbstract;
import com.orientechnologies.orient.core.sql.OCommandSQLParsingException;
import com.orientechnologies.orient.core.sql.OSQLEngine;
import com.orientechnologies.orient.core.sql.filter.OSQLFilter;
import com.orientechnologies.orient.core.sql.query.OSQLAsynchQuery;
import com.tinkerpop.blueprints.Direction;
import com.tinkerpop.blueprints.Edge;
import com.tinkerpop.blueprints.impls.orient.OrientBaseGraph;
import com.tinkerpop.blueprints.impls.orient.OrientEdge;
import com.tinkerpop.blueprints.impls.orient.OrientEdgeType;
import com.tinkerpop.blueprints.impls.orient.OrientGraph;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * SQL DELETE EDGE command.
 * 
 * @author Luca Garulli
 */
public class OCommandExecutorSQLDeleteEdge extends OCommandExecutorSQLRetryAbstract implements OCommandResultListener {
  public static final String NAME    = "DELETE EDGE";
  private ORecordId          rid;
  private String             fromExpr;
  private String             toExpr;
  private int                removed = 0;
  private OCommandRequest    query;
  private OSQLFilter         compiledFilter;
  private OrientGraph        graph;

  @SuppressWarnings("unchecked")
  public OCommandExecutorSQLDeleteEdge parse(final OCommandRequest iRequest) {
    init((OCommandRequestText) iRequest);

    parserRequiredKeyword("DELETE");
    parserRequiredKeyword("EDGE");

    OClass clazz = null;
    String where = null;

    String temp = parseOptionalWord(true);

    final OrientGraph graph = OGraphCommandExecutorSQLFactory.getGraph(false);
    while (temp != null) {

      if (temp.equals("FROM")) {
        fromExpr = parserRequiredWord(false, "Syntax error", " =><,\r\n");
        if (rid != null)
          throwSyntaxErrorException("FROM '" + fromExpr + "' is not allowed when specify a RID (" + rid + ")");

      } else if (temp.equals("TO")) {
        toExpr = parserRequiredWord(false, "Syntax error", " =><,\r\n");
        if (rid != null)
          throwSyntaxErrorException("TO '" + toExpr + "' is not allowed when specify a RID (" + rid + ")");

      } else if (temp.startsWith("#")) {
        rid = new ORecordId(temp);
        if (fromExpr != null || toExpr != null)
          throwSyntaxErrorException("Specifying the RID " + rid + " is not allowed with FROM/TO");

      } else if (temp.equals(KEYWORD_WHERE)) {
        if (clazz == null)
          // ASSIGN DEFAULT CLASS
          clazz = graph.getEdgeType(OrientEdgeType.CLASS_NAME);

        where = parserGetCurrentPosition() > -1 ? " " + parserText.substring(parserGetCurrentPosition()) : "";

        compiledFilter = OSQLEngine.getInstance().parseCondition(where, getContext(), KEYWORD_WHERE);
        break;

      } else if (temp.equals(KEYWORD_RETRY)) {
        parseRetry();
      } else if (temp.length() > 0) {
        // GET/CHECK CLASS NAME
        clazz = graph.getEdgeType(temp);
        if (clazz == null)
          throw new OCommandSQLParsingException("Class '" + temp + " was not found");
      }

      temp = parseOptionalWord(true);
      if (parserIsEnded())
        break;
    }

    if (where == null)
      where = "";
    else
      where = " WHERE " + where;

    if (fromExpr == null && toExpr == null && rid == null)
      if (clazz == null)
        // DELETE ALL THE EDGES
        query = graph.getRawGraph().command(new OSQLAsynchQuery<ODocument>("select from E" + where, this));
      else
        // DELETE EDGES OF CLASS X
        query = graph.getRawGraph().command(new OSQLAsynchQuery<ODocument>("select from " + clazz.getName() + where, this));

    return this;
  }

  /**
   * Execute the command and return the ODocument object created.
   */
  public Object execute(final Map<Object, Object> iArgs) {
    if (fromExpr == null && toExpr == null && rid == null && query == null && compiledFilter == null)
      throw new OCommandExecutionException("Cannot execute the command because it has not been parsed yet");

    for (int r = 0; r < retry; ++r) {
      try {

        if (rid != null) {
          // REMOVE PUNCTUAL RID
          OGraphCommandExecutorSQLFactory.runInTx(new OGraphCommandExecutorSQLFactory.GraphCallBack<Object>() {
            @Override
            public Object call(OrientBaseGraph graph) {
              final OrientEdge e = graph.getEdge(rid);
              if (e != null) {
                e.remove();
                removed = 1;
              }

              return null;
            }
          });
        } else {
          // MULTIPLE EDGES
          final Set<OrientEdge> edges = new HashSet<OrientEdge>();
          if (query == null) {
            OGraphCommandExecutorSQLFactory.runInTx(new OGraphCommandExecutorSQLFactory.GraphCallBack<Object>() {
              @Override
              public Object call(OrientBaseGraph graph) {
                Set<OIdentifiable> fromIds = null;
                if (fromExpr != null)
                  fromIds = OSQLEngine.getInstance().parseRIDTarget(graph.getRawGraph(), fromExpr, context);
                Set<OIdentifiable> toIds = null;
                if (toExpr != null)
                  toIds = OSQLEngine.getInstance().parseRIDTarget(graph.getRawGraph(), toExpr, context);

                if (fromIds != null && toIds != null) {
                  // REMOVE ALL THE EDGES BETWEEN VERTICES
                  for (OIdentifiable fromId : fromIds)
                    for (Edge e : graph.getVertex(fromId).getEdges(Direction.OUT))
                      if (toIds.contains(((OrientEdge) e).getInVertex().getIdentity()))
                        edges.add((OrientEdge) e);
                } else if (fromIds != null)
                  // REMOVE ALL THE EDGES THAT START FROM A VERTEXES
                  for (OIdentifiable fromId : fromIds)
                    edges.add((OrientEdge) graph.getVertex(fromId).getEdges(Direction.OUT));
                else if (toIds != null)
                  // REMOVE ALL THE EDGES THAT ARRIVE TO A VERTEXES
                  for (OIdentifiable toId : toIds)
                    edges.add((OrientEdge) graph.getVertex(toId).getEdges(Direction.IN));
                else
                  throw new OCommandExecutionException("Invalid target");

                if (compiledFilter != null) {
                  // ADDITIONAL FILTERING
                  for (Iterator<OrientEdge> it = edges.iterator(); it.hasNext();) {
                    final OrientEdge edge = it.next();
                    if (!(Boolean) compiledFilter.evaluate((ODocument) edge.getRecord(), null, context))
                      it.remove();
                  }
                }

                // DELETE THE FOUND EDGES
                removed = edges.size();
                for (OrientEdge edge : edges)
                  edge.remove();

                return null;
              }
            });
          } else {
            graph = OGraphCommandExecutorSQLFactory.getGraph(false);
            OGraphCommandExecutorSQLFactory.runInTx(graph, new OGraphCommandExecutorSQLFactory.GraphCallBack<Object>() {
              @Override
              public Object call(OrientBaseGraph graph) {
                // TARGET IS A CLASS + OPTIONAL CONDITION
                return query.execute(iArgs);
              }
            });

          }
        }

        break;

      } catch (OConcurrentModificationException e) {
        if (r + 1 >= retry)
          // NO RETRY; PROPAGATE THE EXCEPTION
          throw e;

        // RETRY?
        if (wait > 0)
          try {
            Thread.sleep(wait);
          } catch (InterruptedException e1) {
          }
      }
    }

    return removed;
  }

  /**
   * Delete the current edge.
   */
  public boolean result(final Object iRecord) {
    final OIdentifiable id = (OIdentifiable) iRecord;

    if (compiledFilter != null) {
      // ADDITIONAL FILTERING
      if (!(Boolean) compiledFilter.evaluate((ODocument) id.getRecord(), null, context))
        return true;
    }

    if (id.getIdentity().isValid()) {
      final OrientEdge e = graph.getEdge(id);

      if (e != null) {
        e.remove();
        removed++;
      }
    }

    return true;
  }

  @Override
  public String getSyntax() {
    return "DELETE EDGE <rid>|FROM <rid>|TO <rid>|<[<class>] [WHERE <conditions>]>";
  }

  @Override
  public void end() {
  }
}
