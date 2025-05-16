import re
import matplotlib.pyplot as plt
from collections import defaultdict
import os

METRICS_TO_PLOT = {
    "delivery_prob": "Delivery Probability",
    "overhead_ratio": "Overhead Ratio",
    "latency_avg": "Latency Average (s)",
    "latency_med": "Latency Median (s)",
    "buffertime_avg": "Buffer Time Average (s)",
    "buffertime_med": "Buffer Time Median (s)",
}

DATA_FILES = [
    "HaggleProphet100_MessageStatsReport.txt",
    "HaggleProphet300_MessageStatsReport.txt",
    "HaggleProphet500_MessageStatsReport.txt",
    "HaggleRandomProphet100_MessageStatsReport.txt",
    "HaggleRandomProphet300_MessageStatsReport.txt",
    "HaggleRandomProphet500_MessageStatsReport.txt",
    "RealityProphet100_MessageStatsReport.txt",
    "RealityProphet300_MessageStatsReport.txt",
    "RealityProphet500_MessageStatsReport.txt",
    "RealityRandomProphet100_MessageStatsReport.txt",
    "RealityRandomProphet300_MessageStatsReport.txt",
    "RealityRandomProphet500_MessageStatsReport.txt",
]

def parse_message_stats_file(filepath):
    """
    Parses a single MessageStatsReport.txt file.
    Extracts prophet type, scenario number, and relevant metrics
    for the new "Haggle/Reality" naming scheme.
    """
    data = {}
    try:
        filename = os.path.basename(filepath)
        fn_lower = filename.lower()

        base_type_str = ""
        if "haggle" in fn_lower:
            base_type_str = "Haggle"
        elif "reality" in fn_lower:
            base_type_str = "Reality"
        else:
            print(f"Warning: Could not determine base type (Haggle/Reality) for {filename}")
            return None

        prophet_variant_str = ""
        if "randomprophet" in fn_lower:
            prophet_variant_str = "Random Prophet"
        elif "prophet" in fn_lower:
            prophet_variant_str = "Prophet"
        else:
            print(f"Warning: Could not determine prophet variant for {filename}")
            return None
        
        prophet_type = f"{base_type_str} {prophet_variant_str}"

        match_scenario = re.search(r"(?:RandomProphet|Prophet)(\d+)_", filename, re.IGNORECASE)
        if not match_scenario:
            print(f"Warning: Could not extract scenario number from {filename} (e.g., Prophet100_).")
            return None
        scenario_number = int(match_scenario.group(1))

        with open(filepath, 'r') as f:
            for line in f:
                line = line.strip()
                if ":" in line:
                    key, value = line.split(":", 1)
                    key = key.strip()
                    value = value.strip()
                    if key in METRICS_TO_PLOT:
                        try:
                            data[key] = float(value)
                        except ValueError:
                            print(f"Warning: Could not convert value '{value}' for key '{key}' in {filename} to float. Skipping.")
                            data[key] = float('nan')

        if not data:
            print(f"Warning: No relevant metrics found in {filename}")
            return None

        return {
            "prophet_type": prophet_type,
            "scenario_number": scenario_number,
            "metrics": data
        }

    except FileNotFoundError:
        print(f"Error: File not found at {filepath}")
        return None
    except Exception as e:
        print(f"Error parsing file {filepath}: {e}")
        return None

if __name__ == "__main__":
    all_parsed_data = []
    
    try:
        script_dir = os.path.dirname(os.path.abspath(__file__))
    except NameError:
        script_dir = os.getcwd()
        
    print(f"Script is running from: {script_dir}")
    print("Looking for data files...")

    for file_entry in DATA_FILES:
        if os.path.isabs(file_entry):
            filepath = file_entry
        else:
            filepath = os.path.join(script_dir, file_entry)

        if not os.path.exists(filepath):
            print(f"File '{file_entry}' (resolved to '{filepath}') not found. Skipping.")
            continue
        
        print(f"Processing file: {filepath}")
        parsed_file_data = parse_message_stats_file(filepath)
        if parsed_file_data:
            all_parsed_data.append(parsed_file_data)

    if not all_parsed_data:
        print("No data successfully parsed. Exiting.")
        exit()

    plotting_data = defaultdict(lambda: defaultdict(list))

    for item in all_parsed_data:
        prophet_type = item["prophet_type"]
        scenario = item["scenario_number"]
        for metric_key, value in item["metrics"].items():
            plotting_data[prophet_type][metric_key].append((scenario, value))

    for prophet_type in plotting_data:
        for metric_key in plotting_data[prophet_type]:
            plotting_data[prophet_type][metric_key].sort(key=lambda x: x[0])

    prophet_types_present = sorted(list(plotting_data.keys()))
    if not prophet_types_present:
        print("No valid prophet types found in the parsed data. Cannot plot.")
        exit()

    for metric_key, metric_label in METRICS_TO_PLOT.items():
        plt.figure(figsize=(10, 6))
        
        has_data_for_metric = False
        for prophet_type in prophet_types_present:
            if metric_key in plotting_data[prophet_type] and plotting_data[prophet_type][metric_key]:
                scenarios, values = zip(*plotting_data[prophet_type][metric_key])
                plt.plot(scenarios, values, marker='o', linestyle='-', label=prophet_type)
                has_data_for_metric = True

        if not has_data_for_metric:
            print(f"No data found for metric: {metric_label}. Skipping plot.")
            plt.close()
            continue

        plt.title(f"{metric_label} Comparison")
        plt.xlabel("Scenario Number (e.g., 100, 300, 500)")
        plt.ylabel(metric_label)
        
        all_scenarios_for_metric = set()
        for pt in prophet_types_present:
            if metric_key in plotting_data[pt] and plotting_data[pt][metric_key]:
                scenarios, _ = zip(*plotting_data[pt][metric_key])
                all_scenarios_for_metric.update(scenarios)
        
        if all_scenarios_for_metric:
            plt.xticks(sorted(list(all_scenarios_for_metric)))

        plt.legend()
        plt.grid(True)
        plt.tight_layout()
        plt.show()

    print("Plotting complete.")
