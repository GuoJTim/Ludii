package metadata.ai.heuristics.terms;

import java.util.Arrays;
import java.util.List;

import annotations.Name;
import annotations.Opt;
import game.Game;
import game.equipment.other.Regions;
import main.StringRoutines;
import main.collections.FVector;
import metadata.ai.heuristics.transformations.HeuristicTransformation;
import metadata.ai.misc.Pair;
import other.context.Context;
import other.location.Location;
import other.state.owned.Owned;

/**
 * Defines a heuristic term based on the proximity of pieces to a particular region.
 * 
 * @author Dennis Soemers
 */
public class RegionProximity extends HeuristicTerm
{
	
	//-------------------------------------------------------------------------
	
	/** Array of names specified for piece types */
	private final String[] pieceWeightNames;
	
	/** 
	 * Array of weights as specified in metadata. Will be used to initialise
	 * a weight vector for a specific game when init() is called.
	 */
	private final float[] gameAgnosticWeightsArray;
	
	/** Vector with weights for every piece type */
	private FVector pieceWeights = null;
	
	/** The maximum distance that exists in our Centres distance table */
	private int maxDistance = -1;
	
	/** Region for which to compute proximity */
	private final int region;

	//-------------------------------------------------------------------------
	
	/**
	 * Constructor
	 * 
	 * @param transformation An optional transformation to be applied to any 
	 * raw heuristic score outputs.
	 * @param weight The weight for this term in a linear combination of multiple terms.
	 * If not specified, a default weight of $1.0$ is used.
	 * @param region Index of the region to which we wish to compute proximity.
	 * @param pieceWeights Weights for different piece types. If no piece weights are
	 * specified at all, all piece types are given an equal weight of $1.0$. If piece
	 * weights are only specified for some piece types, all other piece types get a
	 * weight of $0$.
	 * 
	 * @example (regionProximity weight:-1.0 region:0)
	 */
	public RegionProximity
	(
		@Name @Opt final HeuristicTransformation transformation,
		@Name @Opt final Float weight,
		@Name final Integer region,
		@Name @Opt final Pair[] pieceWeights
	)
	{
		super(transformation, weight);
		
		this.region = region.intValue();
		
		if (pieceWeights == null)
		{
			// We want a weight of 1.0 for everything
			pieceWeightNames = new String[]{""};
			gameAgnosticWeightsArray = new float[]{1.f};
		}
		else
		{
			pieceWeightNames = new String[pieceWeights.length];
			gameAgnosticWeightsArray = new float[pieceWeights.length];
			
			for (int i = 0; i < pieceWeights.length; ++i)
			{
				pieceWeightNames[i] = pieceWeights[i].key();
				gameAgnosticWeightsArray[i] = pieceWeights[i].floatVal();
			}
		}
	}
	
	@Override
	public HeuristicTerm copy()
	{
		return new RegionProximity(this);
	}
	
	/**
	 * Copy constructor (private, so not visible to grammar)
	 * @param other
	 */
	private RegionProximity(final RegionProximity other)
	{
		super(other.transformation, Float.valueOf(other.weight));
		this.pieceWeightNames = Arrays.copyOf(other.pieceWeightNames, other.pieceWeightNames.length);
		this.gameAgnosticWeightsArray = Arrays.copyOf(other.gameAgnosticWeightsArray, other.gameAgnosticWeightsArray.length);
		this.region = other.region;
	}
	
	//-------------------------------------------------------------------------

	@Override
	public float computeValue(final Context context, final int player, final float absWeightThreshold)
	{
		if (maxDistance == 0)
			return 0.f;
		
		final int[] distances = context.game().distancesToRegions()[region];		
		final Owned owned = context.state().owned();

		final List<? extends Location>[] pieces = owned.positions(player);
		float value = 0.f;

		for (int i = 0; i < pieces.length; ++i)
		{
			if (pieces[i].isEmpty())
				continue;
			
			final float pieceWeight = pieceWeights.get(owned.reverseMap(player, i));
			
			if (Math.abs(pieceWeight) >= absWeightThreshold)
			{
				for (final Location position : pieces[i])
				{
					final int site = position.site();
					if (site >= distances.length)	// Different container, skip it
						continue;
					
					final int dist = distances[site];
					
					final float proximity = 1.f - ((float) dist / maxDistance);
					value += pieceWeight * proximity;
				}
			}
		}

		return value;
	}
	
	@Override
	public FVector computeStateFeatureVector(final Context context, final int player)
	{
		final FVector featureVector = new FVector(pieceWeights.dim());
		
		if (maxDistance != 0)
		{
			final int[] distances = context.game().distancesToRegions()[region];		
			final Owned owned = context.state().owned();

			final List<? extends Location>[] pieces = owned.positions(player);

			for (int i = 0; i < pieces.length; ++i)
			{
				final int compIdx = owned.reverseMap(player, i);
						
				for (final Location position : pieces[i])
				{
					final int site = position.site();
					if (site >= distances.length)	// Different container, skip it
						continue;
					
					final int dist = distances[site];
					
					final float proximity = 1.f - ((float) dist / maxDistance);
					featureVector.addToEntry(compIdx, proximity);
				}
			}
		}
		
		return featureVector;
	}
	
	@Override
	public FVector paramsVector()
	{
		return pieceWeights;
	}
	
	@Override
	public void init(final Game game)
	{
		// Compute vector of piece weights
		this.pieceWeights = HeuristicTerm.pieceWeightsVector(game, pieceWeightNames, gameAgnosticWeightsArray);
		
		// Precompute maximum distance for this game
		computeMaxDist(game);
	}
	
	@Override
	public int updateParams(final Game game, final FVector newParams, final int startIdx)
	{
		final int retVal = super.updateParams(game, newParams, startIdx);
		
		// Need to update the array of weights we were passed in constructor
		// in case we decide to write ourselves to a file
		updateGameAgnosticWeights(game, pieceWeights, pieceWeightNames, gameAgnosticWeightsArray);
		
		return retVal;
	}

	//-------------------------------------------------------------------------
	
	/**
	 * Helper method for constructors
	 * @param game
	 */
	private final void computeMaxDist(final Game game)
	{
		final int[] distances = game.distancesToRegions()[region];
		
		if (distances != null)
		{
			int max = 0;
			
			for (int i = 0; i < distances.length; ++i)
			{
				if (distances[i] > max)
					max = distances[i];
			}
			
			maxDistance = max;
		}
		else
		{
			maxDistance = 0;
		}
	}
	
	//-------------------------------------------------------------------------
	
	/**
	 * @param game
	 * @return True if heuristic of this type could be applicable to given game
	 */
	public static boolean isApplicableToGame(final Game game)
	{
		if (game.distancesToRegions() == null)
			return false;
		
		if (game.equipment().components().length <= 1)
			return false;
		
		final Regions[] regions = game.equipment().regions();
		
		if (regions.length == 0)
			return false;
		
		for (int i = 0; i < regions.length; ++i)
		{
			if (game.distancesToRegions()[i] != null)
				return true;
		}
		
		return false;
	}
	
	//-------------------------------------------------------------------------
	
	/**
	 * @return The index of the region for which we compute proximity
	 */
	public int region()
	{
		return region;
	}
	
	//-------------------------------------------------------------------------
	
	@Override
	public String toString()
	{
		final StringBuilder sb = new StringBuilder();
		
		sb.append("(regionProximity");
		if (transformation != null)
			sb.append(" transformation:" + transformation.toString());
		if (weight != 1.f)
			sb.append(" weight:" + weight);
		
		sb.append(" region:" + region);
		
		if (pieceWeightNames.length > 1 || (pieceWeightNames.length == 1 && pieceWeightNames[0].length() > 0))
		{
			sb.append(" pieceWeights:{\n");
			
			for (int i = 0; i < pieceWeightNames.length; ++i)
			{
				sb.append("        (pair " + StringRoutines.quote(pieceWeightNames[i]) + " " + gameAgnosticWeightsArray[i] + ")\n");
			}
			
			sb.append("    }");
		}
		
		sb.append(")");
		
		return sb.toString();
	}
	
	//-------------------------------------------------------------------------
	
	@Override
	public String toStringThresholded(final float threshold)
	{
		boolean shouldPrint = false;
		boolean haveRelevantPieces = false;
		final StringBuilder pieceWeightsSb = new StringBuilder();
		
		if (pieceWeightNames.length > 1 || (pieceWeightNames.length == 1 && pieceWeightNames[0].length() > 0))
		{
			for (int i = 0; i < pieceWeightNames.length; ++i)
			{
				if (Math.abs(weight * gameAgnosticWeightsArray[i]) >= threshold)
				{
					pieceWeightsSb.append("        (pair " + StringRoutines.quote(pieceWeightNames[i]) + " " + gameAgnosticWeightsArray[i] + ")\n");
					haveRelevantPieces = true;
					shouldPrint = true;
				}
			}
		}
		else if (Math.abs(weight) >= threshold)
		{
			// No manually specified weights, so they will all default to 1.0,
			// and we have a large enough term-wide weight
			shouldPrint = true;
		}
		
		if (shouldPrint)
		{
			final StringBuilder sb = new StringBuilder();
		
			sb.append("(regionProximity");
			if (transformation != null)
				sb.append(" transformation:" + transformation.toString());
			if (weight != 1.f)
				sb.append(" weight:" + weight);
			
			sb.append(" region:" + region);
			
			if (haveRelevantPieces)
			{
				sb.append(" pieceWeights:{\n");
				sb.append(pieceWeightsSb);
				sb.append("    }");
			}
			sb.append(")");
			
			return sb.toString();
		}
		else
		{
			return null;
		}
	}
	
	//-------------------------------------------------------------------------

}
