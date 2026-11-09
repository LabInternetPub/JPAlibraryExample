# Reservation Expiry and book Return

This document outlines the design and implementation of the reservation expiry and book return features in the JPA library example.

It wants to mimic the naive and better approaches found in the Affora project (../affora-professors) when dealing with seat reservation 
and expiry method. 
In the library domain:
+ A book can be reserved by a member, and the reservation has an expiry date. The reservation is active until the expiry date or until the book is checked out, whichever comes first.
+ A reserved book can be checked out by a member, and the checkout date is recorded. The state is changed to checked out. The book cannot be checked out after the expiry date. ( a new reservation must be made if the book is not checked out before the expiry date)
+ There is a scheduled process that looks for expired reservations and marks them as expired, printing a message to the console with the member's email and the book's barcode.
+ In the catalog a book must appear as available even if the reservation is active but the due date has passed. The book is only unavailable if it is checked out or if the reservation is still active and the due date has not passed.
+ There is a maximum number of books that a member can reserve at the same time. If a member tries to reserve more than the maximum, an exception is thrown.

In the Affora domain:
+ A set of seats can be held by a customer, and the hold has an expiry date. That would correspond to reserving a book in the library domain.
+ A set of seats can be confirmed by a customer, and the confirmation date is recorded. The seats cannot be confirmed after the expiry date. That would correspond to checking out a book in the library domain.
+ There is a scheduled process that looks for expired holds and marks them as expired. That would correspond to the scheduled process that looks for expired reservations in the library domain.
+ The catalog shows the seats as available even if the hold is active but the due date has passed. That would correspond to the catalog showing a book as available even if the reservation is active but the due date has passed.
+ There is a maximum number of seats that a customer can hold at the same time. If a customer tries to hold more than the maximum, an exception is thrown. That would correspond to the maximum number of books that a member can reserve at the same time in the library domain.

