import pandas as pd
import matplotlib.pyplot as plt
import matplotlib.ticker as mticker
import sys

DEFAULT_FILENAME = 'connection-Bubble_GlobalPopularity3Report.txt'
DEFAULT_NODE_ID = 'A28' 

def plot_node_popularity(filename, node_id_to_plot):
    """
    Loads cumulative popularity data, calculates per-window popularity gain
    for a specific node, and generates a bar plot.

    Args:
        filename (str): Path to the popularity report file (CSV format).
        node_id_to_plot (str): The ID of the node to plot (e.g., 'A28').
    """
    try:
        df = pd.read_csv(filename, index_col=0)
    except FileNotFoundError:
        print(f"Error: File not found at '{filename}'")
        return
    except Exception as e:
        print(f"Error loading file '{filename}': {e}")
        return

    if node_id_to_plot not in df.index:
        print(f"Error: NodeID '{node_id_to_plot}' not found in the file.")
        print(f"Available NodeIDs are: {list(df.index)}")
        return

    node_data = df.loc[node_id_to_plot]

    try:
        cumulative_popularity = pd.to_numeric(node_data, errors='coerce')
        time_points = [int(str(col).split('@')[1]) for col in df.columns]
    except (ValueError, IndexError, AttributeError) as e:
         print(f"Error parsing column headers or data for node {node_id_to_plot}: {e}")
         print("Ensure headers are like 'Pop@<number>' and data is numeric.")
         return
    if cumulative_popularity.isnull().all():
        print(f"Error: Could not convert data to numeric for node {node_id_to_plot}.")
        return

    window_popularity = cumulative_popularity.diff()
    window_popularity.iloc[0] = cumulative_popularity.iloc[0] if not cumulative_popularity.empty else 0

    window_popularity = window_popularity.fillna(0).astype(int)

    window_labels = [f"{i+1}" for i in range(len(time_points))]


    fig, ax = plt.subplots(figsize=(12, 6))

    ax.bar(window_labels, window_popularity, width=0.8)

    interval_hours = "Unknown"
    if len(time_points) > 1:
        interval_seconds = time_points[1] - time_points[0]
        interval_hours = f"{interval_seconds / 3600:.0f}"
    elif len(time_points) == 1:
        interval_seconds = time_points[0]
        interval_hours = f"{interval_seconds / 3600:.0f}"


    ax.set_xlabel(f"Time Window Index (Each window = {interval_hours} hours)")
    ax.set_ylabel("Popularity Gained in Window")
    ax.set_title(f"Popularity per Time Window for Node {node_id_to_plot}")

    max_ticks_display = 30
    if len(window_labels) > max_ticks_display:
        tick_spacing = max(1, len(window_labels) // max_ticks_display)
        ax.xaxis.set_major_locator(mticker.MultipleLocator(tick_spacing))
        plt.setp(ax.get_xticklabels(), rotation=45, ha="right")
    elif len(window_labels) > 15:
         plt.setp(ax.get_xticklabels(), rotation=45, ha="right")

    min_pop = window_popularity.min()
    max_pop = window_popularity.max()
    ax.set_ylim(min(0, min_pop) - 1 , max_pop + max(1, max_pop*0.05))

    ax.grid(axis='y', linestyle='--', alpha=0.7)
    plt.tight_layout()

    output_filename = f"popularity_plot_{node_id_to_plot}_3report.png"
    try:
        plt.savefig(output_filename, dpi=300)
        print(f"Plot saved as {output_filename}")
    except Exception as e:
        print(f"Error saving plot to {output_filename}: {e}")

    plt.close(fig)

if __name__ == "__main__":
    filename_to_use = DEFAULT_FILENAME
    node_id_to_use = DEFAULT_NODE_ID

    if len(sys.argv) > 1:
        node_id_to_use = sys.argv[1]
        print(f"Using NodeID from command line: {node_id_to_use}")
    if len(sys.argv) > 2:
        filename_to_use = sys.argv[2]
        print(f"Using filename from command line: {filename_to_use}")

    plot_node_popularity(filename_to_use, node_id_to_use)