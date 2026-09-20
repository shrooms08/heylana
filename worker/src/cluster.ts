/**
 * Which network the worker's RPC is really on.
 *
 * CLUSTER says which network payments and sends are for, but RPC_URL is a
 * separate secret, and nothing stopped them disagreeing: a devnet CLUSTER with a
 * mainnet RPC hands the phone mainnet blockhashes, and Seed Vault, set to devnet,
 * rightly refuses the transaction. The RPC's genesis hash settles it, once per
 * RPC address for the life of the worker.
 */
import type { Cluster, Rpc } from './rpc.ts'

export const GENESIS_HASHES: Record<string, string> = {
  '5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d': 'mainnet-beta',
  EtWTRABZaYq6iMfeYKouRu166VU2xqa1wcaWoxPkrZBG: 'devnet',
  '4uhcVJyU9pJkvQyS88uRDiswHXSCkY3zQawwpjk2NsNY': 'testnet',
}

const known = new Map<string, string>()

/** For tests: forget what each cluster's providers turned out to be. */
export function forgetRpcClusters(): void {
  known.clear()
}

/**
 * What network the providers for [cluster] are really on, or null when it cannot be told (a
 * local validator, or no answer). Asked once per cluster for the life of the worker.
 */
export async function rpcCluster(rpc: Rpc, cluster: Cluster): Promise<string | null> {
  const remembered = known.get(cluster)
  if (remembered) return remembered
  try {
    const name = GENESIS_HASHES[String(await rpc('getGenesisHash', [], { cluster }))]
    if (name) known.set(cluster, name)
    return name ?? null
  } catch {
    return null
  }
}

/** A plain sentence when the RPC is on another network than CLUSTER; null when they agree or it cannot be told. */
export async function rpcClusterMismatch(rpc: Rpc, cluster: Cluster): Promise<string | null> {
  const actual = await rpcCluster(rpc, cluster)
  if (!actual || actual === cluster) return null
  return `Heylana's server is set up for ${cluster} but connected to ${actual}, so nothing can be sent until that is fixed.`
}
