FROM grafana/grafana:10.4.3

# Copy provisioning configurations
COPY provisioning/datasources /etc/grafana/provisioning/datasources
COPY provisioning/dashboards /etc/grafana/provisioning/dashboards

# Copy dashboard JSON files
COPY dashboards /etc/grafana/dashboards

EXPOSE 3000
