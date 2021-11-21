import matplotlib.pyplot as plt
import matplotlib.ticker as mtick
import numpy as np
import os

topDir = "app/output_files"
trials = os.listdir(topDir)

print(trials)

def sort_on_first_row(data):
    return data[:, data[0, :].argsort()]

trial_data = {}
for trial in trials:
    # if (trial != "DYNAMIC_REBALANCING_COMPARISON"):
    #     continue

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
                if trial == "PART_DISC" and "hopCount" in run:
                    trial_data[trial][run]["xRuns"].append(settingParts[0])
                elif trial == "PART_DISC" and "maxNumberOfInvites" in run:
                    trial_data[trial][run]["xRuns"].append(settingParts[1])
                elif trial == "SCORE_VS_PERC_LEADERS" and "percentageLeaders" in run:
                    trial_data[trial][run]["xRuns"].append(settingParts[2])
                elif trial == "STATIC_REBALANCING_COMPARISON":
                    trial_data[trial][run]["xRuns"].append(lineParts[1])
                elif trial == "DYNAMIC_REBALANCING_COMPARISON":
                    trial_data[trial][run]["xRuns"].append(np.fromstring(lineParts[1], sep=","))


                j = i + 1
                while j < len(lines) and lines[j].strip().split(":")[0] != "settings":
                    lineParts = lines[j].strip().split(":")
                    if not (lineParts[0] in trial_data[trial][run]["yRuns"]):
                        trial_data[trial][run]["yRuns"][lineParts[0]] = [np.fromstring(lineParts[1], sep=",")]
                    else:
                        trial_data[trial][run]["yRuns"][lineParts[0]].append(np.fromstring(lineParts[1], sep=",")) #np.vstack((trial_data[trial][run]["yRuns"][lineParts[0]], np.fromstring(lineParts[1], sep=",")))
                    
                    j += 1

                i = j

        minItems = 100000000
        minArr = None
        if trial == "DYNAMIC_REBALANCING_COMPARISON":
            for arr in trial_data[trial][run]["xRuns"]:
                if arr.shape[0] < minItems:
                    minItems = arr.shape[0]
                    minArr = arr

            for i in range(len(trial_data[trial][run]["xRuns"])):
                trial_data[trial][run]["xRuns"][i] = trial_data[trial][run]["xRuns"][i][:minItems]

                for yDatName in trial_data[trial][run]["yRuns"]:
                    trial_data[trial][run]["yRuns"][yDatName][i] = trial_data[trial][run]["yRuns"][yDatName][i][:minItems]
                
                

            for yDatName in trial_data[trial][run]["yRuns"]:
                    trial_data[trial][run]["yRuns"][yDatName] = np.array(trial_data[trial][run]["yRuns"][yDatName])
                    #print("yshape", trial_data[trial][run]["yRuns"][yDatName].shape)

            trial_data[trial][run]["xRuns"] = np.array(trial_data[trial][run]["xRuns"]).flatten()
            #print("xshape", trial_data[trial][run]["xRuns"].shape)

        else:
            trial_data[trial][run]["xRuns"] = np.array(trial_data[trial][run]["xRuns"], dtype=object)

        for yDatName in trial_data[trial][run]["yRuns"]:
            trial_data[trial][run]["yRuns"][yDatName] = np.array(trial_data[trial][run]["yRuns"][yDatName]).flatten()

        unique_x = np.unique(trial_data[trial][run]["xRuns"])
        print("unique", unique_x, unique_x.shape)
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

        #print("final_processing", trial_data[trial][run])

    if trial == "PART_DISC":
        fig = plt.figure()
        ax1 = fig.subplots(1)

        x = np.array(trial_data[trial]["hopCount_5.csv"]["x"], dtype=np.double)
        all_data = np.array([
            x, 
            trial_data[trial]["hopCount_5.csv"]["y"]["nOfParticipants"], 
            trial_data[trial]["hopCount_5.csv"]["yErr"]["nOfParticipants"], 
            trial_data[trial]["hopCount_7.csv"]["y"]["nOfParticipants"], 
            trial_data[trial]["hopCount_7.csv"]["yErr"]["nOfParticipants"], 
            trial_data[trial]["hopCount_9.csv"]["y"]["nOfParticipants"], 
            trial_data[trial]["hopCount_9.csv"]["yErr"]["nOfParticipants"]
        ], dtype=np.double)
        sorted_data = sort_on_first_row(all_data)

        for i in range(1, 7, 2):
            ax1.plot(
                sorted_data[0,:], 
                sorted_data[i,:], 
                marker='o',
                label=fr"$I_m = {i+4}$"
            )
            ax1.fill_between(
                sorted_data[0,:],
                sorted_data[i,:] - sorted_data[i+1,:],
                sorted_data[i,:] + sorted_data[i+1,:],
                alpha=0.5
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

        x = np.array(trial_data[trial]["maxNumberOfInvites_3.csv"]["x"], dtype=np.double)
        all_data = np.array([x, 
            trial_data[trial]["maxNumberOfInvites_3.csv"]["y"]["nOfParticipants"], 
            trial_data[trial]["maxNumberOfInvites_3.csv"]["yErr"]["nOfParticipants"], 
            trial_data[trial]["maxNumberOfInvites_4.csv"]["y"]["nOfParticipants"], 
            trial_data[trial]["maxNumberOfInvites_4.csv"]["yErr"]["nOfParticipants"], 
            trial_data[trial]["maxNumberOfInvites_5.csv"]["y"]["nOfParticipants"], 
            trial_data[trial]["maxNumberOfInvites_5.csv"]["yErr"]["nOfParticipants"]
        ], dtype=np.double)
        sorted_data = sort_on_first_row(all_data)
        
        j = 0
        for i in range(1, 7, 2):
            ax1.plot(
                sorted_data[0,:], 
                sorted_data[i,:], 
                marker='o',
                label=fr"$h_c = {j + 3}$"
            )
            ax1.fill_between(
                sorted_data[0,:],
                sorted_data[i,:] - sorted_data[i+1,:],
                sorted_data[i,:] + sorted_data[i+1,:],
                alpha=0.5
            )
            j += 1

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
        
        x = np.array(trial_data[trial]["percentageLeaders_5.csv"]["x"], dtype=np.double) * 100.
        all_data = np.array([
            x, 
            trial_data[trial]["percentageLeaders_5.csv"]["y"]["totalDemandsMet"], 
            trial_data[trial]["percentageLeaders_5.csv"]["yErr"]["totalDemandsMet"], 
            trial_data[trial]["percentageLeaders_7.csv"]["y"]["totalDemandsMet"], 
            trial_data[trial]["percentageLeaders_7.csv"]["yErr"]["totalDemandsMet"],
            trial_data[trial]["percentageLeaders_9.csv"]["y"]["totalDemandsMet"], 
            trial_data[trial]["percentageLeaders_9.csv"]["yErr"]["totalDemandsMet"],
            trial_data[trial]["percentageLeaders_5.csv"]["y"]["nOfRebalanceMes"], 
            trial_data[trial]["percentageLeaders_5.csv"]["yErr"]["nOfRebalanceMes"],
            trial_data[trial]["percentageLeaders_7.csv"]["y"]["nOfRebalanceMes"], 
            trial_data[trial]["percentageLeaders_7.csv"]["yErr"]["nOfRebalanceMes"],
            trial_data[trial]["percentageLeaders_9.csv"]["y"]["nOfRebalanceMes"], 
            trial_data[trial]["percentageLeaders_9.csv"]["yErr"]["nOfRebalanceMes"]
        ], dtype=np.double)
        sorted_data = sort_on_first_row(all_data)

        for i in range(1, 7, 2):
            ax1.plot(
                sorted_data[0,:], 
                sorted_data[i,:], 
                marker='o',
                label=fr"$I_m = {i+4}$"
            )
            ax1.fill_between(
                sorted_data[0,:],
                sorted_data[i,:] - sorted_data[i+1,:],
                sorted_data[i,:] + sorted_data[i+1,:],
                alpha=0.5
            )

        ax1.set_xlim(0, 100)
        ax1.set_ylim(0)
        ax1.set_ylabel("Number of demands met")
        ax1.set_xlabel(r"Percentage of leaders $\rho$")
        ax1.xaxis.set_major_formatter(mtick.PercentFormatter())

        ax1.grid()

        ax1.legend(loc="lower right")

        fig.savefig(f"{trial}_percentageLeaders_score.pdf")
        
        fig = plt.figure()
        ax1 = fig.subplots(1)
        
        for i in range(7, 13, 2):
            ax1.plot(
                sorted_data[0,:], 
                sorted_data[i,:], 
                marker='o',
                label=fr"$I_m = {i-6+4}$"
            )
            ax1.fill_between(
                sorted_data[0,:],
                sorted_data[i,:] - sorted_data[i+1,:],
                sorted_data[i,:] + sorted_data[i+1,:],
                alpha=0.5
            )

        ax1.set_xlim(0, 100)
        ax1.set_ylim(0)
        ax1.set_ylabel("Number of messages send")
        ax1.set_xlabel(r"Percentage of leaders $\rho$")
        ax1.xaxis.set_major_formatter(mtick.PercentFormatter())

        ax1.grid()

        ax1.legend()

        fig.savefig(f"{trial}_percentageLeaders_mes.pdf")
    elif trial == "STATIC_REBALANCING_COMPARISON":
        width = 0.35

        fig = plt.figure()
        ax1 = fig.subplots(1)
        
        proto_types = trial_data[trial]["score_complete_graph.txt.csv"]["x"]
        proto_types[np.where(proto_types == "CoinWasher")] = "Our protocol"

        y_all = np.array([
            trial_data[trial]["score_complete_graph.txt.csv"]["y"]["totalDemandsMet"],  
            trial_data[trial]["score_difficult_graph.txt.csv"]["y"]["totalDemandsMet"], 
            trial_data[trial]["score_lightning.csv"]["y"]["totalDemandsMet"],
            trial_data[trial]["score_complete_graph.txt.csv"]["y"]["nOfRebalanceMes"],  
            trial_data[trial]["score_difficult_graph.txt.csv"]["y"]["nOfRebalanceMes"], 
            trial_data[trial]["score_lightning.csv"]["y"]["nOfRebalanceMes"],  
            trial_data[trial]["score_complete_graph.txt.csv"]["y"]["time"],  
            trial_data[trial]["score_difficult_graph.txt.csv"]["y"]["time"], 
            trial_data[trial]["score_lightning.csv"]["y"]["time"], 
        ], dtype=np.double)
        yErr_all = np.array([
            trial_data[trial]["score_complete_graph.txt.csv"]["yErr"]["totalDemandsMet"], 
            trial_data[trial]["score_difficult_graph.txt.csv"]["yErr"]["totalDemandsMet"], 
            trial_data[trial]["score_lightning.csv"]["yErr"]["totalDemandsMet"],
            trial_data[trial]["score_complete_graph.txt.csv"]["yErr"]["nOfRebalanceMes"],  
            trial_data[trial]["score_difficult_graph.txt.csv"]["yErr"]["nOfRebalanceMes"], 
            trial_data[trial]["score_lightning.csv"]["yErr"]["nOfRebalanceMes"],  
            trial_data[trial]["score_complete_graph.txt.csv"]["yErr"]["time"],  
            trial_data[trial]["score_difficult_graph.txt.csv"]["yErr"]["time"], 
            trial_data[trial]["score_lightning.csv"]["yErr"]["time"], 
        ], dtype=np.double)
        
        labels_score = [r"$G_{\mathrm{Complete}}$", r"$G_{\mathrm{Design}}$", r"$G_{\mathrm{Lightning}}$ / $10^4$"]
        labels_mes = [r"$G_{\mathrm{Complete}}$", r"$G_{\mathrm{Design}}$", r"$G_{\mathrm{Lightning}}$ / $10^3$"]
        labels_time = [r"$G_{\mathrm{Complete}}$", r"$G_{\mathrm{Design}}$", r"$G_{\mathrm{Lightning}}$ / $10^2$"]
        x = np.arange(len(labels_score))
        print(y_all)

        y_all[2, :] = y_all[2, :] / 1e4
        yErr_all[2, :] = yErr_all[2, :] / 1e4
        y_all[5, :] = y_all[5, :] / 1e3
        yErr_all[5, :] = yErr_all[5, :] / 1e3
        y_all[8, :] = y_all[8, :] / 1e2
        yErr_all[8, :] = yErr_all[8, :] / 1e2
        
        ax1.grid()
        ax1.set_axisbelow(True)

        ax1.bar(x - width/2, y_all[:3,0], width, label=proto_types[0])
        ax1.bar(x + width/2, y_all[:3,1], width, label=proto_types[1])
        ax1.errorbar(x - width/2, y_all[:3,0], yErr_all[:3, 0], color="black", linestyle="", capsize=8)
        ax1.errorbar(x + width/2, y_all[:3,1], yErr_all[:3, 1], color="black", linestyle="", capsize=8)

        ax1.set_xticks(x)
        ax1.set_xticklabels(labels_score)
        ax1.set_ylabel("Number of demands met")
        
        ax1.legend()
        fig.savefig(f"{trial}_static_comp_1.pdf")

        fig = plt.figure()
        ax1 = fig.subplots(1)

        ax1.grid()
        ax1.set_axisbelow(True)

        ax1.bar(x - width/2, y_all[3:6,0], width, label=proto_types[0])
        ax1.bar(x + width/2, y_all[3:6,1], width, label=proto_types[1])
        ax1.errorbar(x - width/2, y_all[3:6,0], yErr_all[3:6, 0], color="black", linestyle="", capsize=8)
        ax1.errorbar(x + width/2, y_all[3:6,1], yErr_all[3:6, 1], color="black", linestyle="", capsize=8)

        ax1.set_xticks(x)
        ax1.set_xticklabels(labels_mes)
        ax1.set_ylabel("Number of messages send")
        
        ax1.legend()
        fig.savefig(f"{trial}_static_comp_messages.pdf")

        fig = plt.figure()
        ax1 = fig.subplots(1)

        ax1.grid()
        ax1.set_axisbelow(True)

        ax1.bar(x - width/2, y_all[6:,0], width, label=proto_types[0])
        ax1.bar(x + width/2, y_all[6:,1], width, label=proto_types[1])
        ax1.errorbar(x - width/2, y_all[6:,0], yErr_all[6:, 0], color="black", linestyle="", capsize=8)
        ax1.errorbar(x + width/2, y_all[6:,1], yErr_all[6:, 1], color="black", linestyle="", capsize=8)

        ax1.set_xticks(x)
        ax1.set_xticklabels(labels_time)
        ax1.set_ylabel("Total time elapsed (ms)")
        
        ax1.legend()
        fig.savefig(f"{trial}_static_comp_time.pdf")
    elif trial == "DYNAMIC_REBALANCING_COMPARISON":
        print(trial_data[trial]["data_CoinWasher.csv"]["x"])

        fig = plt.figure()
        ax1, ax2 = fig.subplots(2, sharex=True)

        all_coinwasher_data = np.array([
            trial_data[trial]["data_CoinWasher.csv"]["x"] / 1000, 
            trial_data[trial]["data_CoinWasher.csv"]["y"]["successRatio"], 
            trial_data[trial]["data_CoinWasher.csv"]["yErr"]["successRatio"], 
            trial_data[trial]["data_CoinWasher.csv"]["y"]["networkImbalance"], 
            trial_data[trial]["data_CoinWasher.csv"]["yErr"]["networkImbalance"]
        ], dtype=np.double)
        sorted_data = sort_on_first_row(all_coinwasher_data)

        all_revive_data = np.array([
            trial_data[trial]["data_Revive.csv"]["x"] / 1000, 
            trial_data[trial]["data_Revive.csv"]["y"]["successRatio"], 
            trial_data[trial]["data_Revive.csv"]["yErr"]["successRatio"], 
            trial_data[trial]["data_Revive.csv"]["y"]["networkImbalance"], 
            trial_data[trial]["data_Revive.csv"]["yErr"]["networkImbalance"]
        ], dtype=np.double)
        sorted_data = sort_on_first_row(all_revive_data)

        all_no_rebalancing_data = np.array([
            trial_data[trial]["data_Normal.csv"]["x"] / 1000, 
            trial_data[trial]["data_Normal.csv"]["y"]["successRatio"], 
            trial_data[trial]["data_Normal.csv"]["yErr"]["successRatio"], 
            trial_data[trial]["data_Normal.csv"]["y"]["networkImbalance"], 
            trial_data[trial]["data_Normal.csv"]["yErr"]["networkImbalance"]
        ], dtype=np.double)
        sorted_data = sort_on_first_row(all_no_rebalancing_data)
        
        dt = [all_coinwasher_data, all_revive_data, all_no_rebalancing_data]
        labels = ["Our protocol", "Revive", "No rebalancing"]

        for i in range(len(dt)):
            ax1.plot(
                dt[i][0, :], 
                dt[i][1, :], 
                label=labels[i]
            )

            ax1.fill_between(
                dt[i][0,:],
                dt[i][1,:] - dt[i][2,:],
                dt[i][1,:] + dt[i][2,:],
                alpha=0.5
            )

        ax1.set_xlim(0)
        ax1.set_ylim(0, 1)
        ax1.set_ylabel("Success ratio")
        ax1.grid()

        for i in range(len(dt)):
            ax2.plot(
                dt[i][0, :], 
                dt[i][3, :]
            )

            ax2.fill_between(
                dt[i][0,:],
                dt[i][3,:] - dt[i][4,:],
                dt[i][3,:] + dt[i][4,:],
                alpha=0.5
            )

        ax2.set_xlabel("Time (s)")
        ax2.set_ylabel("Average network imbalance")
        ax2.grid()

        fig.legend()

        fig.savefig(f"{trial}_success_ratio.pdf")


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