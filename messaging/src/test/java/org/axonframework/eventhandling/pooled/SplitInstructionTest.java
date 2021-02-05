package org.axonframework.eventhandling.pooled;

import org.axonframework.common.transaction.NoTransactionManager;
import org.axonframework.eventhandling.GlobalSequenceTrackingToken;
import org.axonframework.eventhandling.Segment;
import org.axonframework.eventhandling.TrackerStatus;
import org.axonframework.eventhandling.TrackingToken;
import org.axonframework.eventhandling.tokenstore.TokenStore;
import org.axonframework.eventhandling.tokenstore.UnableToClaimTokenException;
import org.junit.jupiter.api.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Test class validating the {@link SplitInstruction}.
 *
 * @author Steven van Beelen
 */
class SplitInstructionTest {

    private static final String PROCESSOR_NAME = "test";
    private static final int SEGMENT_ID = Segment.ROOT_SEGMENT.getSegmentId();

    private CompletableFuture<Boolean> result;
    private final Map<Integer, WorkPackage> workPackages = new HashMap<>();
    private final TokenStore tokenStore = mock(TokenStore.class);

    private SplitInstruction testSubject;

    private final WorkPackage workPackage = mock(WorkPackage.class);

    @BeforeEach
    void setUp() {
        result = new CompletableFuture<>();

        testSubject = new SplitInstruction(
                result, PROCESSOR_NAME, SEGMENT_ID, workPackages, tokenStore, NoTransactionManager.instance()
        );
    }

    @Test
    void testRunSplitsSegmentFromWorkPackage() throws ExecutionException, InterruptedException {
        Segment testSegmentToSplit = Segment.ROOT_SEGMENT;
        TrackingToken testTokenToSplit = new GlobalSequenceTrackingToken(0);

        TrackerStatus[] expectedTokens = TrackerStatus.split(testSegmentToSplit, testTokenToSplit);
        TrackerStatus expectedOriginal = expectedTokens[0];
        TrackerStatus expectedSplit = expectedTokens[1];

        when(workPackage.segment()).thenReturn(testSegmentToSplit);
        when(workPackage.stopPackage()).thenReturn(CompletableFuture.completedFuture(testTokenToSplit));
        workPackages.put(SEGMENT_ID, workPackage);

        testSubject.run();

        verify(tokenStore).initializeSegment(
                expectedSplit.getTrackingToken(), PROCESSOR_NAME, expectedSplit.getSegment().getSegmentId()
        );
        verify(tokenStore).releaseClaim(PROCESSOR_NAME, expectedOriginal.getSegment().getSegmentId());
        assertTrue(result.isDone());
        assertTrue(result.get());
    }

    @Test
    void testRunSplitsSegmentAfterClaiming() throws ExecutionException, InterruptedException {
        int[] testSegmentIds = {SEGMENT_ID};
        Segment testSegmentToSplit = Segment.computeSegment(SEGMENT_ID, testSegmentIds);
        TrackingToken testTokenToSplit = new GlobalSequenceTrackingToken(0);

        TrackerStatus[] expectedTokens = TrackerStatus.split(testSegmentToSplit, testTokenToSplit);
        TrackerStatus expectedOriginal = expectedTokens[0];
        TrackerStatus expectedSplit = expectedTokens[1];

        when(tokenStore.fetchSegments(PROCESSOR_NAME)).thenReturn(testSegmentIds);
        when(tokenStore.fetchToken(PROCESSOR_NAME, SEGMENT_ID)).thenReturn(testTokenToSplit);

        testSubject.run();

        verify(tokenStore).initializeSegment(
                expectedSplit.getTrackingToken(), PROCESSOR_NAME, expectedSplit.getSegment().getSegmentId()
        );
        verify(tokenStore).releaseClaim(PROCESSOR_NAME, expectedOriginal.getSegment().getSegmentId());
        assertTrue(result.isDone());
        assertTrue(result.get());
    }

    @Test
    void testRunReturnsFalseThroughUnableToClaimTokenException() throws ExecutionException, InterruptedException {
        when(tokenStore.fetchSegments(PROCESSOR_NAME)).thenReturn(new int[]{SEGMENT_ID});
        when(tokenStore.fetchToken(PROCESSOR_NAME, SEGMENT_ID))
                .thenThrow(new UnableToClaimTokenException("some exception"));

        testSubject.run();

        assertTrue(result.isDone());
        assertFalse(result.get());
    }

    @Test
    void testRunReturnsFalseThroughOtherException() throws ExecutionException, InterruptedException {
        when(workPackage.segment()).thenReturn(Segment.ROOT_SEGMENT);
        when(workPackage.stopPackage())
                .thenReturn(CompletableFuture.completedFuture(new GlobalSequenceTrackingToken(0)));
        workPackages.put(SEGMENT_ID, workPackage);

        doThrow(new IllegalStateException("some exception")).when(tokenStore)
                                                            .initializeSegment(any(), eq(PROCESSOR_NAME), anyInt());

        testSubject.run();

        assertTrue(result.isDone());
        assertFalse(result.get());
    }

    @Test
    void testDescription() {
        String result = testSubject.description();
        assertNotNull(result);
        assertTrue(result.contains("Split"));
    }
}