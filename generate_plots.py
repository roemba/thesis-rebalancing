import scipy
import scipy.stats as st
import matplotlib
import matplotlib.pyplot as plt
import numpy as np

trial_names = ["no_rebalancing"]
arr_per_trial = {}

for name in trial_names:
    arr_per_trial[name] = np.loadtxt(f"app/output_files/{name}.csv", delimiter=",")

fig = plt.figure()
ax1, ax2 = fig.subplots(2, sharex=True)

for name in trial_names:
    ax1.plot(arr_per_trial[name][0] / 1000, arr_per_trial[name][1], label=name)
    ax2.plot(arr_per_trial[name][0] / 1000, arr_per_trial[name][2])

ax1.set_xlim(0)
ax1.set_ylim(0, 1)
ax1.set_ylabel("Success ratio")

ax2.set_ylim(0)
ax2.set_xlabel("Time (s)")
ax2.set_ylabel("Average network imbalance")

fig.legend()

fig.savefig("test.pdf")
print("done")

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