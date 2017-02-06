import unittest

import numpy as np
import scipy
import scipy.stats
import sklearn.neighbors
import sklearn.neighbors.kde

from ic2.kernel import Kernel
from ic2.rkde import RKDE


class RKDETest(unittest.TestCase):
    def gauss_helper(self, k, p, e, train_size, test_size):
        mu = np.zeros(k)
        cov = np.diag(np.ones(k))
        dist = scipy.stats.multivariate_normal(mean=mu, cov=cov)
        sample_size = 1000000
        np.random.seed(0)
        threshold = np.percentile(dist.pdf(
            np.random.multivariate_normal(mean=mu, cov=cov, size=sample_size)
        ), p * 100)

        np.random.seed(0)
        training_data = np.random.multivariate_normal(mean=mu, cov=cov, size=train_size)
        bw = train_size ** (-1 / (k + 4))
        kernel = Kernel(k=k, bw=bw)

        t = sklearn.neighbors.KDTree(training_data)
        rkde = RKDE(kernel=kernel, tree=t, threshold=e*threshold)

        np.random.seed(1)
        test_data = np.random.multivariate_normal(mean=mu, cov=cov, size=test_size)
        test_pdfs = np.array([rkde.calc(test_query) for test_query in test_data])
        test_class = test_pdfs < threshold

        actual_kde = sklearn.neighbors.kde.KernelDensity(bandwidth=bw, atol=0.001 * threshold)
        actual_kde.fit(training_data)
        actual_test_pdfs = np.exp(actual_kde.score_samples(test_data))
        actual_class = actual_test_pdfs < threshold

        n_disagree = np.sum(test_class != actual_class)
        return n_disagree, np.sum(test_class), np.sum(actual_class)

    def test_1d(self):
        n, n1, n2 = self.gauss_helper(1, 0.5, 0.01, 1000, 1000)
        self.assertEqual(0, n)

    def test_4d(self):
        n, n1, n2 = self.gauss_helper(4, 0.2, 0.01, 3000, 1000)
        self.assertEqual(0, n)
