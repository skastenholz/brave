package brave.p6spy;

import brave.Span;
import brave.propagation.ThreadLocalSpan;
import com.p6spy.engine.common.StatementInformation;
import com.p6spy.engine.event.SimpleJdbcEventListener;
import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;
import zipkin.Constants;
import zipkin.Endpoint;
import zipkin.TraceKeys;

final class TracingJdbcEventListener extends SimpleJdbcEventListener {

  final String remoteServiceName;
  final boolean includeParameterValues;

  TracingJdbcEventListener(String remoteServiceName, boolean includeParameterValues) {
    this.remoteServiceName = remoteServiceName;
    this.includeParameterValues = includeParameterValues;
  }

  /**
   * Uses {@link ThreadLocalSpan} as there's no attribute namespace shared between callbacks, but
   * all callbacks happen on the same thread.
   *
   * <p>Uses {@link ThreadLocalSpan#CURRENT_TRACER} and this interceptor initializes before tracing.
   */
  @Override public void onBeforeAnyExecute(StatementInformation info) {
    String sql = includeParameterValues ? info.getSqlWithValues() : info.getSql();
    // don't start a span unless there is SQL as we cannot choose a relevant name without it
    if (sql == null || sql.isEmpty()) return;

    // Gets the next span (and places it in scope) so code between here and postProcess can read it
    Span span = ThreadLocalSpan.CURRENT_TRACER.next();
    if (span == null || span.isNoop()) return;

    span.kind(Span.Kind.CLIENT).name(sql.substring(0, sql.indexOf(' ')));
    span.tag(TraceKeys.SQL_QUERY, sql);
    parseServerAddress(info.getConnectionInformation().getConnection(), span);
    span.start();
  }

  @Override public void onAfterAnyExecute(StatementInformation info, long elapsed, SQLException e) {
    Span span = ThreadLocalSpan.CURRENT_TRACER.remove();
    if (span == null || span.isNoop()) return;

    if (e != null) {
      span.tag(Constants.ERROR, Integer.toString(e.getErrorCode()));
    }
    span.finish();
  }

  /**
   * This attempts to get the ip and port from the JDBC URL. Ex. localhost and 5555 from {@code
   * jdbc:mysql://localhost:5555/mydatabase}.
   */
  void parseServerAddress(Connection connection, Span span) {
    try {
      URI url = URI.create(connection.getMetaData().getURL().substring(5)); // strip "jdbc:"
      Endpoint.Builder builder = Endpoint.builder().port(url.getPort());
      boolean parsed = builder.parseIp(url.getHost());
      if (remoteServiceName == null || "".equals(remoteServiceName)) {
        String databaseName = connection.getCatalog();
        if (databaseName != null && !databaseName.isEmpty()) {
          builder.serviceName(databaseName);
        } else {
          if (!parsed) return;
          builder.serviceName("");
        }
      } else {
        builder.serviceName(remoteServiceName);
      }
      span.remoteEndpoint(builder.build());
    } catch (Exception e) {
      // remote address is optional
    }
  }
}
