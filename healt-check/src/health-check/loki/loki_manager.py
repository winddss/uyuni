import subprocess
import io
import os
import requests
import zipfile
import re
import time
from utils import HealthException
from containers.manager import console, build_image, image_exists, container_is_running, podman

# Update this number if adding more targets to the promtail config
PROMTAIL_TARGETS = 6

# Max number of seconds to wait for Loki to be ready
LOKI_WAIT_TIMEOUT = 120


def download_component_build_image(image, config, verbose=False):
    console.log("Building loki image...")
    if image_exists(image):
        console.log(f"[yellow]Skipped as the {image} image is already present")
        return

    # Fetch the logcli binary from the latest release
    url = f"https://github.com/grafana/loki/releases/download/v2.9.2/{image}-linux-amd64.zip"
    #    url = f"https://github.com/grafana/loki/releases/download/v2.8.6/{image}-linux-amd64.zip"
    dest_dir = config.load_dockerfile_dir(image)
    response = requests.get(url)
    zip = zipfile.ZipFile(io.BytesIO(response.content))
    zip.extract(f"{image}-linux-amd64", dest_dir)
    console.log("I'm about to build loki image")
    build_image(image, image_path=dest_dir, verbose=verbose)
    console.log(f"[green]The {image} image was built successfully")

def run_loki(supportconfig_path=None, config=None, verbose=False):
    """
    Run promtail and loki to aggregate the logs
    """
    if container_is_running("loki"):
        console.log("[yellow]Skipped as the loki container is already running")
    else:
        promtail_template = config.load_jinja_template("promtail/promtail.yaml.j2")
        render_promtail_cfg(supportconfig_path, promtail_template, config)
        loki_config_file_path = config.get_config_file_path("loki")
        promtail_config_file_path = config.get_config_file_path("promtail")
        podman(
            [
                "run",
                "--replace",
                "-d",
                "--network",
                "podman",
                "-p",
                "3100:3100",
                "--name",
                "loki",
                "-v",
                f"{loki_config_file_path}:/etc/loki/local-config.yaml",
                "docker.io/grafana/loki",
            ],
            console=console,
        )

        # Run promtail only now since it pushes data to loki
        console.log("[bold]Building promtail image")
        download_component_build_image("promtail", config=config, verbose=verbose)
        podman_args = [
            "run",
            "--replace",
            "--network",
            "podman",
            "-p",
            "9081:9081",            
            "-d",
            "-v",
            f"{promtail_config_file_path}:/etc/promtail/config.yml",
            "-v",
            f"{supportconfig_path}:{supportconfig_path}",
            "--name",
            "promtail",
            "promtail",
        ]

        podman(
            podman_args,
            console=console,
        )

def render_promtail_cfg(supportconfig_path=None, promtail_template=None, config=None):
    """
    Render promtail configuration file

    :param supportconfig_path: render promtail configuration based on this path to a supportconfig
    """

    if supportconfig_path:
        opts = {
            "rhn_logs_path": os.path.join(
                supportconfig_path, "spacewalk-debug/rhn-logs/rhn/"
            ),
            "cobbler_logs_file": os.path.join(
                supportconfig_path, "spacewalk-debug/cobbler-logs/cobbler.log"
            ),
            "salt_logs_path": os.path.join(
                supportconfig_path, "spacewalk-debug/salt-logs/salt/"
            ),
            "postgresql_logs_path": os.path.join(
                supportconfig_path, "spacewalk-debug/database/"
            ),
            "apache2_logs_path": os.path.join(
                supportconfig_path, "spacewalk-debug/httpd-logs/apache2/"
            ),
        }
    else:
        opts = {
            "rhn_logs_path": "/var/log/rhn/",
            "cobbler_logs_file": "/var/log/cobbler.log",
            "salt_logs_path": "/var/log/salt/",
            "apache2_logs_path": "/var/log/apache2/",
            "postgresql_logs_path": "/var/lib/pgsql/data/log/",
        }

    # Write rendered promtail configuration file
    config.write_config("promtail", promtail_template.render(**opts) )


def wait_loki_init():
    """
    Try to figure out when loki is ready to answer our requests.
    There are two things to wait for:
      - loki to be up
      - promtail to have read the logs and the loki ingester having handled them
    """
    metrics = None
    timeouted = False
    start_time = time.time()
    ready = False

    # Wait for promtail to be ready
    # TODO Add a timeout here in case something went really bad
    # TODO checking the lags won't work when working on older logs,
    # we could try to compare the positions with the size of the files in such a case
    while (
        not metrics
        or metrics["active"] < PROMTAIL_TARGETS
        or (not metrics["lags"] and metrics["active_files"] == 0)
        or any([v >= 10 for v in metrics["lags"].values()])
        or (metrics["lags"] and metrics["active_files"])
        or not ready
        and not timeouted
    ):
        console.log("Waiting for promtail metrics to be collected")
        time.sleep(1)
        response = requests.get(f"http://localhost:9081/metrics")
        if response.status_code == 200:
            content = response.content.decode()
            active = re.findall("promtail_targets_active_total ([0-9]+)", content)
            active_files = re.findall("promtail_files_active_total ([0-9]+)", content)
            lags = re.findall(
                'promtail_stream_lag_seconds{filename="([^"]+)".*} ([0-9.]+)', content
            )
            metrics = {
                "lags": {row[0]: float(row[1]) for row in lags},
                "active": int(active[0]) if active else 0,
                "active_files": int(active_files[0]) if active_files else 0,
            }

        # check if loki is ready
        console.log("Waiting for loki to be ready")
        response = requests.get(f"http://localhost:3100/ready")
        if response.status_code == 200:
            content = response.content.decode()
            if content == "ready\n":
                ready = True

        # check if promtail is ready
        console.log("Waiting for promtail to be ready")
        response = requests.get(f"http://localhost:9081/ready")
        if response.status_code == 200:
            content = response.content.decode()
            if content == "Ready":
                ready = True
            else:
                ready = False
        # check timeout
        if (time.time() - start_time) > LOKI_WAIT_TIMEOUT:
            timeouted = True
    if timeouted:
        raise HealthException(
            "[red bold]Timeout has been reached waiting for Loki and promtail. Something unexpected may happen. Please check and try again."
        )
    else:
        console.log("[bold]Loki and promtail are now ready to receive requests")