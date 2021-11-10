import scipy
import scipy.stats as st
import matplotlib
import matplotlib.pyplot as plt
import numpy as np

alpha = 1

r = st.expon.rvs(alpha, size=10000, scale=50)

fig, ax = plt.subplots(1, 1)

ax.hist(r, density=True, histtype='stepfilled', alpha=0.2)

plt.show()