import matplotlib.pyplot as plt
import numpy as np
import os

topDir = "app/output_files"
trials = os.listdir(topDir)

print(trials)

trial_data = {}
for trial in trials:
    trial_data[trial] = {}
    runFiles = os.listdir(f"{topDir}/{trial}")
    print(len(runFiles))

    for run in runFiles:
        lines = []
        with open(f"{topDir}/{trial}/{run}") as f:
            lines = f.readlines()

        trial_data[trial][run] = {
            "xRuns": [],
            "yRuns": {},
            "x": [],
            "y": {},
            "yErr": {}
        }

        i = 0
        while i < len(lines):
            currentLine = lines[i]
            lineParts = currentLine.strip().split(":")
            if lineParts[0] == "settings":
                settingParts = lineParts[1].split("_")
                if run == "hopCount.csv":
                    trial_data[trial][run]["xRuns"].append(settingParts[0])
                elif run == "maxNumberOfInvites.csv":
                    trial_data[trial][run]["xRuns"].append(settingParts[1])
                elif run == "percentageLeaders.csv":
                    trial_data[trial][run]["xRuns"].append(settingParts[2])

                j = i + 1
                while j < len(lines) and lines[j].strip().split(":")[0] != "settings":
                    lineParts = lines[j].strip().split(":")
                    if not (lineParts[0] in trial_data[trial][run]["yRuns"]):
                        trial_data[trial][run]["yRuns"][lineParts[0]] = np.fromstring(lineParts[1], sep=",")
                    else:
                        trial_data[trial][run]["yRuns"][lineParts[0]] = np.concatenate((trial_data[trial][run]["yRuns"][lineParts[0]], np.fromstring(lineParts[1], sep=",")), axis=0)
                    
                    j += 1

                i = j
                

        trial_data[trial][run]["xRuns"] = np.array(trial_data[trial][run]["xRuns"], dtype=np.double)

        unique_x = np.unique(trial_data[trial][run]["xRuns"], axis=0)
        trial_data[trial][run]["x"] = unique_x

        for xU in unique_x:
            unique_indices = np.where(trial_data[trial][run]["xRuns"] == xU)
            for yDatName in trial_data[trial][run]["yRuns"]:
                yPerX = trial_data[trial][run]["yRuns"][yDatName][unique_indices]

                if not (yDatName in trial_data[trial][run]["y"]):
                    trial_data[trial][run]["y"][yDatName] = []
                    trial_data[trial][run]["yErr"][yDatName] = []

                trial_data[trial][run]["y"][yDatName].append(np.average(yPerX))
                trial_data[trial][run]["yErr"][yDatName].append(np.std(yPerX))

        for yDatName in trial_data[trial][run]["y"]:
            trial_data[trial][run]["y"][yDatName] = np.array(trial_data[trial][run]["y"][yDatName])

        print(trial_data[trial][run])



    if trial == "PART_DISC":
        fig = plt.figure()
        ax1 = fig.subplots(1)
        
        ax1.plot(
            trial_data[trial]["hopCount.csv"]["x"], 
            trial_data[trial]["hopCount.csv"]["y"]["nOfParticipants"], 
            marker='o',
            label="Mean"
        )
        ax1.fill_between(
            trial_data[trial]["hopCount.csv"]["x"],
            trial_data[trial]["hopCount.csv"]["y"]["nOfParticipants"] - trial_data[trial]["hopCount.csv"]["yErr"]["nOfParticipants"],
            trial_data[trial]["hopCount.csv"]["y"]["nOfParticipants"] + trial_data[trial]["hopCount.csv"]["yErr"]["nOfParticipants"],
            alpha=0.5,
            label="One standard deviation"
        )
        ax1.set_xlim(1)
        ax1.set_ylim(0)
        ax1.set_ylabel("Number of participants $|P|$")
        ax1.set_xlabel("Hop count $h_c$")

        ax1.grid()

        ax1.legend()

        fig.savefig(f"{trial}_hopCount.pdf")

        fig = plt.figure()
        ax1 = fig.subplots(1)
        
        ax1.plot(
            trial_data[trial]["maxNumberOfInvites.csv"]["x"], 
            trial_data[trial]["maxNumberOfInvites.csv"]["y"]["nOfParticipants"],
            marker='o',
            label="Mean"
        )
        ax1.fill_between(
            trial_data[trial]["maxNumberOfInvites.csv"]["x"],
            trial_data[trial]["maxNumberOfInvites.csv"]["y"]["nOfParticipants"] - trial_data[trial]["maxNumberOfInvites.csv"]["yErr"]["nOfParticipants"],
            trial_data[trial]["maxNumberOfInvites.csv"]["y"]["nOfParticipants"] + trial_data[trial]["maxNumberOfInvites.csv"]["yErr"]["nOfParticipants"],
            alpha=0.5,
            label="One standard deviation"
        )
        ax1.set_xlim(1)
        ax1.set_ylim(0)
        ax1.set_ylabel("Number of participants $|P|$")
        ax1.set_xlabel("Maxmimum number of invites per node $I_m$")

        ax1.grid()
        ax1.legend(loc="upper left")

        fig.savefig(f"{trial}_maxNumberOfInvites.pdf")
    elif trial == "SCORE_VS_PERC_LEADERS":
        fig = plt.figure()
        ax1 = fig.subplots(1)
        
        ax1.plot(
            trial_data[trial]["percentageLeaders.csv"]["x"], 
            trial_data[trial]["percentageLeaders.csv"]["y"]["totalDemandsMet"], 
            marker='o',
            label="Mean"
        )
        ax1.fill_between(
            trial_data[trial]["percentageLeaders.csv"]["x"],
            trial_data[trial]["percentageLeaders.csv"]["y"]["totalDemandsMet"] - trial_data[trial]["percentageLeaders.csv"]["yErr"]["totalDemandsMet"],
            trial_data[trial]["percentageLeaders.csv"]["y"]["totalDemandsMet"] + trial_data[trial]["percentageLeaders.csv"]["yErr"]["totalDemandsMet"],
            alpha=0.5,
            label="One standard deviation"
        )

        ax1.set_xlim(0)
        ax1.set_ylim(0)
        ax1.set_ylabel("Number of demands met")
        ax1.set_xlabel("Percentage of leaders")

        ax1.grid()

        ax1.legend(loc="lower right")

        fig.savefig(f"{trial}_percentageLeaders_score.pdf")
        
        fig = plt.figure()
        ax1 = fig.subplots(1)
        
        ax1.plot(
            trial_data[trial]["percentageLeaders.csv"]["x"], 
            trial_data[trial]["percentageLeaders.csv"]["y"]["nOfRebalanceMes"], 
            marker='o',
            label="Mean"
        )
        ax1.fill_between(
            trial_data[trial]["percentageLeaders.csv"]["x"],
            trial_data[trial]["percentageLeaders.csv"]["y"]["nOfRebalanceMes"] - trial_data[trial]["percentageLeaders.csv"]["yErr"]["nOfRebalanceMes"],
            trial_data[trial]["percentageLeaders.csv"]["y"]["nOfRebalanceMes"] + trial_data[trial]["percentageLeaders.csv"]["yErr"]["nOfRebalanceMes"],
            alpha=0.5,
            label="One standard deviation"
        )

        ax1.set_xlim(0)
        ax1.set_ylim(0)
        ax1.set_ylabel("Number of messages send")
        ax1.set_xlabel("Percentage of leaders")

        ax1.grid()

        ax1.legend()

        fig.savefig(f"{trial}_percentageLeaders_mes.pdf")

# trial_names = ["no_rebalancing", "coinwasher", "revive"]
# arr_per_trial = {}

# for name in trial_names:
#     arr_per_trial[name] = np.loadtxt(f"app/output_files/{name}.csv", delimiter=",")

# fig = plt.figure()
# ax1, ax2 = fig.subplots(2, sharex=True)

# for name in trial_names:
#     ax1.plot(arr_per_trial[name][0] / 1000, arr_per_trial[name][1], label=name)
#     ax2.plot(arr_per_trial[name][0] / 1000, arr_per_trial[name][2])

# ax1.set_xlim(0)
# ax1.set_ylim(0, 1)
# ax1.set_ylabel("Success ratio")

# ax2.set_ylim(0)
# ax2.set_xlabel("Time (s)")
# ax2.set_ylabel("Average network imbalance")

# fig.legend()

# fig.savefig("test.pdf")
# print("done")

# val plt = Plot.create()
# plt.plot()
#     .add(x, y)
#     .label("label")
#     .linestyle("-")
# plt.xlabel("Time (ms)")
# plt.ylabel("Success ratio")
# // plt.ylim(0, 1)
# plt.xlim(0, x[x.size - 1])
# //plt.text(0.5, 0.2, "text")
# //plt.legend()
# plt.savefig("success_ratio.pdf")
# plt.executeSilently()