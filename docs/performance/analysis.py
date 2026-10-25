#!/usr/bin/env python3
"""Predeclared paired startup analysis; pure offline, no device or network access."""
from __future__ import annotations
import math
import random
import statistics


def require(value, message):
    if not value:
        raise ValueError(message)


def quantile_linear(values, probability):
    require(values and 0 <= probability <= 1, 'Invalid quantile inputs')
    values = sorted(values)
    position = (len(values) - 1) * probability
    lower = math.floor(position)
    upper = math.ceil(position)
    return values[lower] + (values[upper] - values[lower]) * (position - lower)


def analyze(samples, protocol):
    require(protocol['rounds'] == 3 and protocol['pairsPerRound'] == 7 and protocol['totalPairs'] == 21, 'Unexpected fixed trial design')
    require(len(samples) == 42, 'Exactly 42 scored launches are required')
    require(all(row.get('phase') == 'scored-ordinary-cold-start' and row.get('passed') is True for row in samples), 'Every scored launch must have passed functional checks')
    require(all(row.get('preconditioningPassed') is True and row.get('automaticSettle', {}).get('passed') is True for row in samples), 'Every precondition and automatic feature check must pass')
    metric = protocol['primaryMetric']
    per_round = []
    difference_groups = []
    all_differences = []
    pooled = {'baseline': [], 'candidate': []}
    all_metrics = sorted(samples[0]['timings'])
    expected_order = [(rnd, pair, label) for rnd in range(1, 4) for pair in range(1, 8)
                      for label in (['baseline', 'candidate'] if (rnd + pair) % 2 == 0 else ['candidate', 'baseline'])]
    require([(r['round'], r['pairInRound'], r['variant']) for r in samples] == expected_order, 'Trial sequence differs from the preregistered AB/BA design')
    for ordinal, row in enumerate(samples, 1):
        require(row['ordinal'] == ordinal, 'Samples were dropped or reordered')
        require(row['pair'] == (row['round'] - 1) * 7 + row['pairInRound'], 'Pair identity mismatch')
        require(set(row['timings']) == set(all_metrics), 'Metric schema differs between samples')
        require(all(isinstance(v, (int, float)) and not isinstance(v, bool) and math.isfinite(v) and v >= 0 for v in row['timings'].values()), 'Invalid timing value')
        require(row['timings'][metric] > 0, 'Primary first-draw timing must be positive')
        pooled[row['variant']].append(row['timings'][metric])
    for rnd in range(1, 4):
        rows = [r for r in samples if r['round'] == rnd]
        grouped = {label: [r for r in rows if r['variant'] == label] for label in pooled}
        medians = {label: statistics.median(r['timings'][metric] for r in grouped[label]) for label in pooled}
        differences = []
        for pair in range(1, 8):
            members = {r['variant']: r for r in rows if r['pairInRound'] == pair}
            require(set(members) == set(pooled), 'Incomplete baseline/candidate pair')
            differences.append(members['candidate']['timings'][metric] - members['baseline']['timings'][metric])
        difference_groups.append(differences)
        all_differences.extend(differences)
        ratio = medians['candidate'] / medians['baseline']
        per_round.append({'round': rnd, 'baselineMedianNs': medians['baseline'], 'candidateMedianNs': medians['candidate'],
                          'candidateBaselineRatio': ratio, 'medianImprovementPercent': (1 - ratio) * 100,
                          'pairedDifferencesNs': differences, 'pairedWins': sum(d < 0 for d in differences),
                          'medianGatePassed': medians['candidate'] <= medians['baseline'] * protocol['gates']['eachRoundCandidateMedianAtMostBaselineRatio'],
                          'secondaryMedianNs': {key: {label: statistics.median(r['timings'][key] for r in grouped[label]) for label in pooled}
                                                for key in all_metrics if key != metric}})
    spec = protocol['bootstrap']
    require(spec['iterations'] == 20000 and spec['randomSeed'] == 21320261025, 'Bootstrap differs from the registered plan')
    rng = random.Random(spec['randomSeed'])
    bootstrap = [statistics.median([group[rng.randrange(7)] for group in difference_groups for _ in range(7)])
                 for _ in range(spec['iterations'])]
    lower, upper = quantile_linear(bootstrap, .025), quantile_linear(bootstrap, .975)
    wins = sum(d < 0 for d in all_differences)
    p90 = {label: quantile_linear(values, .9) for label, values in pooled.items()}
    gates = {'allRoundMedianImprovements': all(r['medianGatePassed'] for r in per_round),
             'pooledPairedWins': wins >= protocol['gates']['pooledPairedWinsAtLeast'],
             'pairedBootstrapUpperBound': upper < protocol['gates']['bootstrap95PercentUpperBoundStrictlyBelowNs'],
             'pooledP90': p90['candidate'] <= p90['baseline'] * protocol['gates']['pooledP90CandidateAtMostBaselineRatio'],
             'allFunctionalChecks': True, 'allThreeRoundsComplete': True}
    return {'passed': all(gates.values()), 'gates': gates, 'failedGates': [key for key, value in gates.items() if not value],
            'rounds': per_round, 'pairedDifferencesNs': all_differences, 'pairedWins': wins,
            'pairedTies': sum(d == 0 for d in all_differences), 'pairedLosses': sum(d > 0 for d in all_differences),
            'medianPairedDifferenceNs': statistics.median(all_differences),
            'pooled': {label: {'samplesNs': values, 'medianNs': statistics.median(values), 'p90Ns': p90[label],
                               'minNs': min(values), 'maxNs': max(values)} for label, values in pooled.items()},
            'bootstrap': {**spec, 'lowerNs': lower, 'upperNs': upper},
            'interpretation': 'These preregistered gates apply only to this exact candidate and stopped-state, real signed-in, app-cache-empty test setup. Network and device variance remain; secondary metrics do not replace first-draw gates.'}
