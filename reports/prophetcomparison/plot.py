import matplotlib.pyplot as plt
import numpy as np
import re
import glob # To find files
import os   # To work with file paths

# --- Configuration ---
# Directory where the data files are located.
# Use '.' for the current directory where the script is running.
# Or specify a path like 'path/to/your/data/files'
DATA_DIRECTORY = '.'
FILE_PATTERN = '*_MessageStatsReport.txt' # Pattern to match the data files

# --- Data Parsing ---
data = {}

# Find all files matching the pattern in the specified directory
file_path_pattern = os.path.join(DATA_DIRECTORY, FILE_PATTERN)
report_files = glob.glob(file_path_pattern)

if not report_files:
    print(f"Error: No files found matching '{file_path_pattern}'.")
    print("Please check the DATA_DIRECTORY and FILE_PATTERN variables.")
    exit() # Stop the script if no files are found

print(f"Found {len(report_files)} report files to process:")
# Sort files for consistent processing order (optional but good practice)
report_files.sort()
for filename in report_files:
    print(f"  - Processing: {os.path.basename(filename)}") # Show only filename part

    # Extract protocol and scenario from the filename
    basename = os.path.basename(filename) # Get filename without directory path
    match_filename = re.match(r'(prophetrandomtuned|prophettuned)_scenario(\d+)_', basename)

    if not match_filename:
        print(f"    Warning: Could not parse protocol/scenario from filename: {basename}. Skipping this file.")
        continue # Skip to the next file if filename format is unexpected

    protocol_name = "Prophet Random" if match_filename.group(1) == "prophetrandomtuned" else "Prophet"
    scenario_num = int(match_filename.group(2))

    # Initialize dictionaries if needed
    if protocol_name not in data:
        data[protocol_name] = {}
    if scenario_num not in data[protocol_name]:
         data[protocol_name][scenario_num] = {}

    # Read the content of the file
    try:
        with open(filename, 'r') as f:
            lines = f.readlines() # Read all lines into a list
    except Exception as e:
        print(f"    Error reading file {filename}: {e}. Skipping this file.")
        continue

    # Extract key metrics from the file content
    for line in lines:
        line = line.strip() # Remove leading/trailing whitespace
        if ':' in line:
            # Use regex for more robust splitting (handles potential extra spaces)
            match_kv = re.match(r'([^:]+):\s*(.*)', line)
            if match_kv:
                key = match_kv.group(1).strip()
                value_str = match_kv.group(2).strip()

                # Store only the metrics we need
                if key in ['delivery_prob', 'overhead_ratio', 'latency_avg', 'latency_med', 'buffertime_avg', 'buffertime_med']:
                    try:
                        data[protocol_name][scenario_num][key] = float(value_str)
                    except ValueError:
                         # Handle cases like 'NaN' or other non-float values
                         print(f"    Warning: Could not convert value '{value_str}' for key '{key}' in {basename} to float. Storing as NaN.")
                         data[protocol_name][scenario_num][key] = np.nan
            # else: # Optional: uncomment to see lines that have ':' but don't match the key: value pattern
            #     print(f"    Debug: Line '{line}' in {basename} has ':' but didn't match key: value pattern.")


# --- Data Preparation for Plotting ---

# Check if any data was successfully parsed
if not data:
    print("\nError: No data was successfully parsed from the files. Cannot generate plots.")
    exit()

protocols = list(data.keys())
protocols.sort() # Ensure consistent order (e.g., Prophet, Prophet Random)

# Get scenarios from the first protocol (assuming all protocols have the same scenarios)
# Add error handling in case a protocol dictionary is empty
try:
    first_proto_scenarios = list(data[protocols[0]].keys())
    if not first_proto_scenarios:
        print(f"Error: No scenarios found for the first protocol '{protocols[0]}'. Cannot proceed.")
        exit()
    scenarios = sorted(first_proto_scenarios)
except (IndexError, KeyError):
      print("Error: Could not determine scenarios from the parsed data.")
      exit()


print(f"\nProtocols found: {protocols}")
print(f"Scenarios found: {scenarios}")

metrics_to_plot = [
    ('delivery_prob', 'Delivery Probability', ''),
    ('overhead_ratio', 'Overhead Ratio', ''),
    ('latency_avg', 'Latency Average', '(time units)'),
    ('latency_med', 'Latency Median', '(time units)'),
    ('buffertime_avg', 'Buffer Time Average', '(time units)'),
    ('buffertime_med', 'Buffer Time Median', '(time units)')
]

plot_data = {}
missing_data_warnings = []
for proto in protocols:
    plot_data[proto] = {}
    for metric_key, _, _ in metrics_to_plot:
        metric_values = []
        for sc in scenarios:
            # Use .get() to handle potentially missing scenario/metric combinations gracefully
            value = data.get(proto, {}).get(sc, {}).get(metric_key, np.nan)
            if np.isnan(value):
                 warning_msg = f"Warning: Missing data for Protocol='{proto}', Scenario='{sc}', Metric='{metric_key}'"
                 if warning_msg not in missing_data_warnings: # Avoid duplicate warnings
                     missing_data_warnings.append(warning_msg)
            metric_values.append(value)
        plot_data[proto][metric_key] = metric_values

# Print any missing data warnings collected during preparation
if missing_data_warnings:
    print("\n--- Data Warnings ---")
    for warning in missing_data_warnings:
        print(warning)
    print("NaN values will be used in plots where data is missing.")
    print("---------------------\n")


# --- Plotting (Identical to previous line graph version) ---

fig, axes = plt.subplots(2, 3, figsize=(18, 10)) # Create a 2x3 grid of plots
axes = axes.ravel() # Flatten the 2D array of axes for easy iteration

# Define markers and linestyles for better visual distinction
markers = ['o', 's', '^', 'd', 'v', '*']
linestyles = ['-', '--', ':', '-.']

for i, (metric_key, metric_label, unit_label) in enumerate(metrics_to_plot):
    ax = axes[i]

    # Plot lines for each protocol
    for j, proto in enumerate(protocols):
        ax.plot(scenarios, plot_data[proto][metric_key],
                label=proto,
                marker=markers[j % len(markers)],       # Cycle through markers
                linestyle=linestyles[j % len(linestyles)]) # Cycle through linestyles

    # Add labels, title, and legend
    ax.set_ylabel(f"{metric_label} {unit_label}".strip())
    ax.set_xlabel("Scenario Number") # X-axis now represents the scenario number
    ax.set_title(f'{metric_label} Trend')
    ax.set_xticks(scenarios) # Ensure ticks are exactly at the scenario numbers
    ax.set_xticklabels(scenarios) # Label the ticks
    ax.legend()
    ax.grid(True, linestyle='--', alpha=0.7) # Add grid lines

# Adjust layout to prevent overlap
plt.tight_layout(pad=3.0)

# Show the plot
plt.show()
